"""CPU video memory estimate for ordered batches (not measured peak memory)."""
from memory_policy import MemoryPreflightError, memory_override_active
from runtime_resources import available_memory

MIB = 1024**2


def color_workers(value):
    if type(value) is not int or not 1 <= value <= 32:
        raise ValueError('调色帧并发须为 1–32 的整数')
    return value


def plan_color_workers(requested, source_size, output_size, total_frames, batch_size):
    workers = color_workers(requested)
    batch = max(workers, color_workers(batch_size))
    if total_frames > 0:
        workers, batch = min(workers, total_frames), min(batch, total_frames)
    sw, sh = source_size
    w, h = output_size
    if min(sw, sh, w, h) <= 0:
        raise ValueError('视频尺寸无效')
    need = 32*MIB + batch*(sw*sh*3 + w*h*9) + workers*(128*MIB + w*h*112)
    override = memory_override_active()
    budget = None if override else max(0, int(available_memory()*.72) - 64*MIB)
    if not override and need > budget:
        raise MemoryPreflightError(f'视频估算需 {need//MIB} MiB，可用预算 {budget//MIB} MiB。请降低并发或调整尺寸。')
    return dict(mode='frame-threadpool', requestedWorkers=requested, effectiveWorkers=workers,
                batchSize=batch, colorKind='cpu-lut', ramEstimateBytes=need, ramBudgetBytes=budget,
                estimateOnly=True, memoryPreflightOverridden=override, exactUint8Lookup=False)
