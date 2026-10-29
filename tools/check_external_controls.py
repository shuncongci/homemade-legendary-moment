"""Static layout regression, run from the public project root; no Android access."""
from pathlib import Path
p=Path(__file__).resolve().parents[1]/'app/src/main/java/xyz/shuncongci/leica/ExternalModeActivity.kt'
t=p.read_text('utf8')
start='start=button("开始监听（跳过现有全部照片）"){startListening()};add(start)'
stop='stop=button("停止监听并取消排队"){ExternalModeService.stop(this)};add(stop)'
assert t.count(start)==t.count(stop)==1
assert t.index('add(header,0)')<t.index(start)<t.index(stop)<t.index('add(text("拍一张')<t.index('        buildPermissions()')
assert 'start.isEnabled=!s.running && !browsing;stop.isEnabled=s.running && !s.stopping' in t
print('PASS_EXTERNAL_CONTROLS_TOP_ORDER_AND_CALLBACKS')
