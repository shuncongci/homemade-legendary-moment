"""Truthful workbench telemetry; elapsed time never advances computational progress.

Only allowlisted scalar counters/geometry cross the HTTP boundary. Engine messages,
paths, model metadata and image bytes never enter public events. A whole-model call
without counters is explicitly indeterminate, not a guessed overall percentage.
"""
import math
import time

LABELS = {
    'prepare': '准备本地引擎', 'reading': '读取与校正原片',
    'subject': '识别与细化主体', 'depth': '计算原片相对深度',
    'color': '迁移色彩与融合细节', 'bokeh': '生成景深与高光散景',
    'composition': '分析构图方案', 'smart-focus': '解析智能焦点', 'crop': '应用构图裁切',
    'hdr-source': '重建真实源HDR', 'hdr-saving': '编码与回读HDR增益图',
    'playback': '生成兼容播放预览',
    'frames': '逐帧调色与编码', 'saving': '保存与校验成片',
}
PHASE_INFO = {
    'prepare': ('prepare', '准备本地引擎', '首次使用需启动本地计算环境并检查设备，可能需要较长时间；模型加载单独计时。'),
    'engine-spawn-ready': ('prepare', '本地计算进程已启动', '计算进程已启动，基础组件已加载；尚未开始处理图像。'),
    'engine-worker-ready': ('prepare', '本地计算进程已就绪', '正在初始化处理环境，尚未加载图像模型。'),
    'engine-pipeline-import': ('prepare', '加载处理组件', '正在加载通用处理组件；调色组件会在需要时加载。'),
    'engine-pipeline-ready': ('prepare', '处理组件已加载', '接下来检查计算设备与所需组件。'),
    'engine-runtime-select': ('prepare', '检查本地计算环境', '在独立进程中检查组件并试运行设备，检查期间请稍候。'),
    'engine-runtime-probed': ('prepare', '计算环境检查完成', '检查用时包含下方的组件加载和设备试运行分项，请勿重复相加。'),
    'engine-torch-ready': ('prepare', '计算组件已加载', '处理所需的计算库已加载；此项与前面的环境检查分别计时。'),
    'engine-device-ready': ('prepare', '计算设备已设置', '计算设备和线程已设置，尚未开始图像模型计算。'),
    'engine-runtime-cached': ('prepare', '复用已检查的计算环境', '当前计算环境已通过检查，无需重复检查。'),
    'engine-threads-ready': ('prepare', 'CPU 线程设置完成', '已应用你的 CPU 线程设置；此项仅记录线程设置耗时。'),
    'reading': ('reading', '读取与校正原片', '读取工作图并检查处理尺寸；不修改源文件。'),
    'reading-ready': ('reading', '原片读取与校正完成', '已完成解码、方向校正、尺寸处理、色彩配置和原片校验；不含引擎启动耗时。'),
    'subject': ('subject', '识别与细化主体', '按所选保护点生成遮罩，不自动选择主体。'),
    'subject-plan': ('subject', '检查可复用的保护点', '复用同一原片、相同处理尺寸下的保护点结果，只计算本次缺少的部分。'),
    'subject-load': ('subject', '校验与加载主体模型', '正在检查并加载本地主体保护模型。'),
    'subject-sam': ('subject', '识别选中主体', '正在识别保护点所在的主体，此步骤暂不显示百分比。'),
    'subject-matte': ('subject', '细化主体边缘', '正在分块细化主体边缘，保留透明和半透明区域。'),
    'subject-point': ('subject', '保护点计算完成', '计数表示已完成识别与细化的保护点；增加并发不一定更快。'),
    'subject-union': ('subject', '合并保护区域', '正在合并各保护点的结果，并检查保护区是否覆盖所选位置。'),
    'subject-ready': ('subject', '主体保护计算完成', '保护区已生成，正在准备预览。'),
    'subject-cached': ('subject', '复用已计算的保护区', '原片、保护点和尺寸未变，无需重新计算。'),
    'depth': ('depth', '计算原片相对深度', '正在分析画面深度，此步骤暂不显示百分比，请稍候。'),
    'depth-ready': ('depth', '原片深度计算完成', '相对深度用于预览与景深，不表示物理距离。'),
    'depth-input': ('depth', '准备完整深度分析图', '按你设置的深度分析尺寸准备图像，不改变成片尺寸。'),
    'depth-load': ('depth', '校验与加载深度模型', '首次使用需检查并加载模型；之后可复用，模型闲置时保存在内存而非显存中。'),
    'depth-infer': ('depth', '计算图像深度', '正在计算整张分析图的深度，此步骤暂不显示百分比。'),
    'depth-normalize': ('depth', '检查与整理深度结果', '计算已结束，正在检查深度数值并整理为可使用的范围。'),
    'depth-metrics': ('depth', '深度计算完成', '可查看模型加载、图像准备、深度计算和结果整理的耗时。'),
    'depth-validated': ('depth', '深度结果检查完成', '深度结果已通过检查；超出磁盘缓存容量时仍可用于本次处理。'),
    'depth-cached': ('depth', '复用完整深度结果', '原片、分析尺寸和计算设备未变，无需重新加载模型或计算。'),
    'gan_color': ('color', '迁移色彩与融合细节', '正在应用色彩风格并融合细节，此步骤暂不显示百分比。'),
    'color-ready': ('color', '调色计算完成', '调色结果可供过程预览，尚非最终成片。'),
    'bokeh-start': ('bokeh', '准备焦点与景深', '按已计算的深度和你设置的焦点准备景深效果。'),
    'bokeh-tile': ('bokeh', '分块计算景深', '计数表示当前已完成的虚化图像块。'),
    'bokeh-pass': ('bokeh', '一轮虚化计算完成', '当前一轮虚化已完成，仍需完成高光与最终合成。'),
    'highlight-tile': ('bokeh', '全分辨率高光合成', '正在逐块合成高光和遮挡关系。'),
    'bokeh-ready': ('bokeh', '景深合成完成', '虚化与主体保护已合成，正在保存成片。'),
    'smart-focus': ('smart-focus', '解析智能焦点', '取最高评分构图方案的中心对焦，不自动裁切；视频按每帧独立分析。'),
    'composition': ('composition', '分析构图方案', '本地构图模型推理，不改变原片。'),
    'crop': ('crop', '应用选定构图', '按当前选择裁切成片及对应原片预览。'),
    'playback-encode': ('playback', '编码兼容播放预览', '仅转为浏览器兼容的 VP8/Opus 预览，不显影、不替换原片或正式成片；可取消。'),
    'playback-verify': ('playback', '回读兼容播放预览', '完整检查预览帧数和尺寸，未完成前不发布。'),
    'video-read': ('reading', '定位与解码视频', '正在读取所选时间的画面。'),
    'grading': ('frames', '逐帧调色与编码', '计数表示已完成调色并送入编码器的视频帧。'),
    'muxing': ('saving', '音轨同步与视频封装', '逐帧处理已结束；等待音轨封装和文件检查。'),
    'saving': ('saving', '保存与校验成片', '保存完整工作尺寸及预览，完成后才可导出新版本。'),
    'hdr-source': ('hdr-source', '重建真实源HDR', '正在读取原片的 HDR 增益信息；仅支持含增益图的原片。'),
    'hdr-saving': ('hdr-saving', '编码与回读HDR增益图', '正在生成并检查新的 HDR 增益图，检查通过后保存。'),
    'device_fallback': (None, '切换至 CPU 重试', '显卡计算失败，正在使用 CPU 按相同尺寸重新计算，请稍候。'),
}


def new_progress(action, options):
    ids = ['prepare', 'reading']
    if action == 'photo':
        if options.get('smartFocus') and options.get('blurEnabled'): ids.extend(['smart-focus', 'composition'])
        if options.get('subjectEnabled') and options.get('blurEnabled'): ids.append('subject')
        if options.get('blurEnabled'): ids.append('depth')
        if options.get('hdrEnabled'): ids.append('hdr-source')
        if options.get('colorEnabled'): ids.append('color')
        if options.get('blurEnabled'): ids.append('bokeh')
        if options.get('crop'): ids.append('crop')
        if options.get('hdrEnabled'): ids.append('hdr-saving')
    elif action in ('subject', 'depth', 'composition'): ids.append(action)
    elif action in ('video', 'video-preview') and options.get('blurEnabled'):
        ids.extend(['smart-focus' if options.get('smartFocus') else 'bokeh', 'frames'])
    elif action == 'video-playback': ids.append('playback')
    elif action == 'video': ids.append('frames')
    elif action == 'video-preview': ids.append('color')
    ids.append('saving')
    return {'version': 1, 'sequence': 0, 'stage': None, 'code': 'queued',
            'label': '等待处理', 'message': '本地引擎按提交顺序处理，排队不计为计算。',
            'indeterminate': True, 'units': None, 'detail': {}, 'events': [], 'visuals': {},
            'steps': [{'id': k, 'label': LABELS[k], 'status': 'pending'} for k in ids]}


def finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def counters(detail):
    detail = detail if isinstance(detail, dict) else {}
    clean = {}
    for key in ('completed', 'total', 'pass', 'passes', 'scale', 'pointIndex', 'points', 'tileIndex', 'tiles', 'fps', 'etaSeconds',
                'sourceWidth', 'sourceHeight', 'analysisWidth', 'analysisHeight', 'analysisSeconds',
                'modelLoadSeconds', 'inputSeconds', 'deviceTransferSeconds', 'inferenceSeconds', 'outputSeconds', 'normalizeSeconds',
                'decodeOrientSeconds', 'rgbResizeSeconds', 'iccSeconds', 'sourceHashSeconds',
                'workerStartupSeconds', 'workerSetupSeconds', 'pipelineImportSeconds', 'runtimeProbeSeconds',
                'torchImportSeconds', 'runtimeSetupSeconds', 'threadConfigSeconds', 'runtimeProbeAttempts',
                'runtimeProbeFailures', 'probeTorchImportSeconds', 'probeVisionImportSeconds',
                'probeThreadConfigSeconds', 'probeKernelSeconds', 'concurrency', 'requestedConcurrency', 'effectiveConcurrency',
                'reusedPoints', 'pendingPoints', 'computedPoints'):
        value = detail.get(key)
        if finite(value) and 0 <= value <= 2**53-1: clean[key] = value
    if detail.get('cacheSource') in ('memory', 'disk', 'inference'):
        clean['cacheSource'] = detail['cacheSource']
    for key in ('modelReused', 'runtimeReused'):
        if type(detail.get(key)) is bool:
            clean[key] = detail[key]
    rect = detail.get('rect')
    if isinstance(rect, (list, tuple)) and len(rect) == 4 and all(finite(v) and 0 <= v <= 1 for v in rect):
        if rect[2] > rect[0] and rect[3] > rect[1]: clean['rect'] = list(rect)
    unit = detail.get('unit')
    if unit in ('tiles', 'frames', 'points', 'files'): clean['unit'] = unit
    return clean


def update_progress(job, code, detail=None, now=None):
    p = job['progress']
    if job['status'] in ('done', 'error', 'canceled') or code not in PHASE_INFO: return
    now = time.time() if now is None else now
    stage, label, message = PHASE_INFO.get(code, (p['stage'], p['label'], p['message']))
    steps = p['steps']
    if stage not in {s['id'] for s in steps}: stage = p['stage']
    if stage != p['stage']:
        for s in steps:
            if s['status'] == 'running':
                s.update(status='done', finishedAt=now)
        chosen = next((s for s in steps if s['id'] == stage), None)
        if chosen:
            # Retry is explicit: never pretend a second attempt was already done.
            chosen.update(status='running', startedAt=now)
            chosen.pop('finishedAt', None)
        p['stage'] = stage
    elif stage:
        chosen = next(s for s in steps if s['id'] == stage)
        if chosen['status'] == 'pending': chosen.update(status='running', startedAt=now)
    clean = counters(detail)
    # Keep bounded scalar diagnostics even when per-tile events roll out of history.
    metrics = {k: v for k, v in clean.items() if k.endswith('Seconds') or k in
               ('sourceWidth', 'sourceHeight', 'analysisWidth', 'analysisHeight', 'modelReused', 'cacheSource',
                'runtimeReused', 'runtimeProbeAttempts', 'runtimeProbeFailures', 'concurrency', 'requestedConcurrency', 'effectiveConcurrency',
                 'reusedPoints', 'pendingPoints', 'computedPoints')}
    if metrics:
        p['metrics'] = {**p.get('metrics', {}), **metrics}
    units = None
    if clean.get('total', 0) > 0 and 'completed' in clean and clean['completed'] <= clean['total']:
        units = {'completed': clean['completed'], 'total': clean['total'], 'unit': clean.get('unit', 'tiles')}
    p.update(sequence=p['sequence']+1, code=code, label=label, message=message,
             detail=clean, units=units, indeterminate=units is None, updatedAt=now)
    job['phase'] = label
    # This field remains only a compatibility milestone for older clients.
    # New clients must use actual stage counters, never treat it as compute %.
    completed = sum(s['status'] == 'done' for s in steps)
    job['percent'] = min(99, round(completed * 100 / len(steps)))
    event = {'sequence': p['sequence'], 'code': code, 'stage': stage, 'label': label, 'at': now, 'detail': clean}
    if p['events'] and p['events'][-1]['code'] == code:
        p['events'][-1] = event  # Keep latest unit count; no unbounded per-tile history.
    else:
        p['events'] = (p['events'] + [event])[-64:]


def finish_progress(job, status, now=None):
    now = time.time() if now is None else now
    p = job['progress']
    for step in p['steps']:
        if step['status'] == 'running': step.update(status='done' if status == 'done' else status, finishedAt=now)
        elif status == 'done' and step['status'] == 'pending':
            # A branch may legitimately skip bokeh on unreliable depth. Do not mark unobserved work done.
            step['status'] = 'skipped'
    label = {'done': '处理完成', 'error': '处理失败', 'canceled': '已取消'}[status]
    p.update(sequence=p['sequence']+1, code=status, label=label, indeterminate=False,
             units=None, updatedAt=now, message='处理结果已就绪。' if status == 'done' else '本次未发布新成片，原片及上次完整结果保留。')
    p['events'] = (p['events'] + [{'sequence':p['sequence'], 'code':status, 'label':label, 'stage':p['stage'], 'at':now, 'detail':{}}])[-64:]
