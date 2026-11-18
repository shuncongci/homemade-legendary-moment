"""Public desktop checks; only generated media is used."""
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
from unittest.mock import patch

import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT/'app'), str(ROOT/'src')]

from legend import public_assets
from legend.leica_all_colors import LeicaColorManager
from workbench import WorkbenchServer


def checksum(data):
    return hashlib.sha256(data).hexdigest()


def photo():
    y, x = np.indices((64, 96))
    rgb = ((x*2, y*3, (x+y)*2))
    array = np.asarray(rgb, dtype=np.uint8).transpose(1, 2, 0).copy()
    with io.BytesIO() as f, Image.fromarray(array) as image:
        image.save(f, 'PNG')
        return f.getvalue(), array


class AssetChecks(unittest.TestCase):
    def test_hashes_and_fail_closed(self):
        for style in public_assets.ASSETS:
            self.assertTrue(public_assets.verified_asset(style))
        data = public_assets.verified_asset('leica-instant')
        with tempfile.TemporaryDirectory(prefix='legend-check-') as tmp, patch.object(public_assets, 'ASSET_ROOT', Path(tmp)):
            with self.assertRaisesRegex(ValueError, '找不到'):
                public_assets.verified_asset('leica-instant')
            target = public_assets.asset_path('leica-instant')
            target.parent.mkdir()
            target.write_bytes(data[:-1] + bytes([data[-1] ^ 1]))
            with self.assertRaisesRegex(ValueError, '校验失败'):
                public_assets.verified_asset('leica-instant')


class WorkbenchChecks(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix='legend-test-')
        self.addCleanup(self.folder.cleanup)
        self.server = WorkbenchServer(('127.0.0.1',0), folder=Path(self.folder.name)/'session', persistent=True)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        self.addCleanup(self.stop)
        self.url = 'http://127.0.0.1:'+str(self.server.server_port)
        self.token = self.get('/api/status')['token']

    def stop(self):
        self.server.shutdown()
        self.worker.join(timeout=5)
        self.server.server_close()

    def request(self, path, data=None, headers=None):
        return urllib.request.urlopen(urllib.request.Request(self.url+path, data=data, headers=headers or {}), timeout=15)

    def get(self, path):
        with self.request(path) as f:return json.load(f)

    def post(self, path, payload):
        with self.request(path, json.dumps(payload).encode('utf-8'), {'Content-Type': 'application/json', 'X-Workbench-Token':self.token}) as f:
            return json.load(f)

    def upload(self, name, data, kind='photo'):
        with self.request('/api/import?'+urllib.parse.urlencode({'name':name,'kind':kind}), data, {'X-Workbench-Token':self.token,'Content-Type':'application/octet-stream'}) as f:
            return json.load(f)['input']

    def result(self, payload):
        job=self.post('/api/jobs',payload)['job']['id']
        deadline=time.monotonic()+95
        while time.monotonic()<deadline:
            found=next(j for j in self.get('/api/status')['jobs'] if j['id']==job)
            if found['status'] in {'done','error','canceled','interrupted'}:
                self.assertEqual(found['status'],'done',found.get('error'))
                return found['result']
            time.sleep(.05)
        self.fail('显影未到终态')

    def test_page_and_policy(self):
        with self.request('/') as f:
            self.assertIn(b'data-public-unavailable',f.read())
            self.assertIn("connect-src 'self'",f.headers['Content-Security-Policy'])
        with self.assertRaises(urllib.error.HTTPError) as e:
            self.request('/api/status',headers={'Origin':'https://example.invalid'})
        self.assertEqual(e.exception.code,403)
        with self.assertRaises(urllib.error.HTTPError) as e:
            self.request('/api/jobs', b'{}')
        self.assertEqual(e.exception.code,403)
        status=self.get('/api/status')
        self.assertEqual({s['id'] for s in status['styles']},set(public_assets.ASSETS))
        self.assertEqual(status['capabilities']['videoStyles'],list(public_assets.VIDEO_STYLES))
        self.assertFalse(status['capabilities']['depth'])
        self.assertFalse(status['capabilities']['subjectProtection'])
        self.assertFalse(status['capabilities']['composition'])
        self.assertEqual(status['config']['device_preference'],'cpu')
        item=self.upload('fixture.png',photo()[0])
        for parameters in [{'style':'not-published'}, {'blurEnabled':True}, {'action':'depth'}, {'action':'composition'}, {'action':'subject'}]:
            with self.assertRaises(urllib.error.HTTPError) as e:
                self.post('/api/jobs',{'inputId':item['id'],**parameters})
            self.assertEqual(e.exception.code,400)
        self.assertEqual(self.get('/api/status')['jobs'],[])

    def test_photo_color_and_export(self):
        blob, array=photo()
        item=self.upload('fixture.png',blob)
        colors=LeicaColorManager()
        for style in public_assets.ASSETS:
            with self.subTest(style=style):
                result=self.result({'action':'photo','inputId':item['id'],'style':style})
                self.assertEqual(result['device'],'cpu')
                self.assertEqual((result['width'],result['height']),(96,64))
                with self.request(result['downloadUrl']) as f, Image.open(io.BytesIO(f.read())) as saved:
                    outputs=np.asarray(saved.convert('RGB'))
                self.assertFalse(np.array_equal(outputs,array))
                self.assertTrue(np.array_equal(outputs,colors.grade(array,style=style)))
        job=self.get('/api/status')['jobs'][-1]
        export=self.post('/api/export',{'id':job['id'],'format':'jpeg-95'})['export']
        with self.request(export['downloadUrl']) as f, Image.open(io.BytesIO(f.read())) as saved:
            self.assertEqual((saved.format,saved.size),('JPEG',(96,64)))
        self.assertEqual(checksum(self.server.workbench.inputs[item['id']]['path'].read_bytes()),checksum(blob))

    def test_video_preview_and_final(self):
        generated=Path(self.folder.name)/'synthetic.mp4'
        writer=cv2.VideoWriter(str(generated),cv2.VideoWriter_fourcc(*'mp4v'),12,(96,64))
        self.assertTrue(writer.isOpened())
        try:
            for i in range(8):writer.write(cv2.cvtColor(np.roll(photo()[1],i,axis=1),cv2.COLOR_RGB2BGR))
        finally:
            writer.release()
        blob=generated.read_bytes()
        item=self.upload('synthetic.mp4',blob,'video')
        with self.assertRaises(urllib.error.HTTPError) as error:
            self.post('/api/jobs',{'action':'video', 'inputId':item['id'], 'style':'leica-cool'})
        self.assertEqual(error.exception.code,400)
        for style in public_assets.VIDEO_STYLES:
            with self.subTest(style=style):
                preview=self.result({'action':'video-preview','inputId':item['id'],'style':style,'ratio':.5})
                self.assertEqual((preview['width'],preview['height']),(96,64))
                self.assertNotEqual(preview['originalBase64'],preview['gradedBase64'])
                result=self.result({'action':'video','inputId':item['id'],'style':style})
                self.assertEqual((result['frames'],result['width'],result['height']),(8,96,64))
                with self.request(result['downloadUrl']) as f:
                    output=Path(self.folder.name)/f'{style}.mp4'; output.write_bytes(f.read())
                cap=cv2.VideoCapture(str(output))
                try:
                    count=0
                    while (pair:=cap.read())[0]:
                        self.assertEqual(pair[1].shape,(64,96,3)); count+=1
                    self.assertEqual(count,8)
                finally:
                    cap.release()
        self.assertEqual(self.server.workbench.inputs[item['id']]['path'].read_bytes(),blob)


if __name__=='__main__':
    unittest.main()
