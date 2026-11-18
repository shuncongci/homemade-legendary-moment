"""Photo color stage; publishing and workspace checks live in workbench.py."""
import base64
import io

import numpy as np
from PIL import Image

from execution_policy import scoped_execution
from legend.instant_refinement import refinement_scope
from legend.leica_all_colors import LeicaColorManager


class LeicaPipeline:
    device = 'cpu'

    def __init__(self):
        self.colors = LeicaColorManager()

    @scoped_execution
    def process_image(self, image, *, style, progress, hdr_pair=None, checkpoint=None):
        progress('gan_color', '正在显影')
        if checkpoint:
            checkpoint = checkpoint.scope('desktop-public-cpu-v1')

        def grade():
            check = getattr(getattr(checkpoint, 'archive', checkpoint), 'check', lambda: progress('gan_color'))
            with refinement_scope(check):
                return Image.fromarray(self.colors.grade(np.asarray(image), style=style))

        colored = checkpoint.compute('color', grade) if checkpoint else grade()
        try:
            with colored.copy() as preview, io.BytesIO() as buffer:
                preview.thumbnail((640, 640), Image.Resampling.LANCZOS)
                preview.save(buffer, format='JPEG', quality=85)
                progress('color-ready', '', dict(previewBase64=base64.b64encode(buffer.getvalue()).decode('ascii'), displayOnly=True))
            report = {'color': {'style': style, 'device': 'cpu', 'backend': self.colors.backend(style)}}
            if hdr_pair is not None:
                from hdr_photo import transfer
                action = lambda: transfer(*hdr_pair, colored)
                report['_hdrLinear'] = checkpoint.compute('hdr-transfer', action) if checkpoint else action()
            return colored, report
        except BaseException:
            colored.close()
            raise
