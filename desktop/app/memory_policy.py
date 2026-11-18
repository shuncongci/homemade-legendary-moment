"""Single-action admission override. Never an allocator/OOM handler or saved option."""
from contextlib import contextmanager
from contextvars import ContextVar

MEMORY_PREFLIGHT = 'memory_preflight'
_override = ContextVar('legend_memory_override', default=False)


class MemoryPreflightError(ValueError):
    """Only estimated RAM/VRAM admission failures carry this retryable type."""


def memory_override_active():
    return _override.get() is True


@contextmanager
def memory_override_scope(enabled=False):
    if type(enabled) is not bool:
        raise ValueError('内存预检授权必须为明确开关')
    token = _override.set(enabled)
    try:
        yield
    finally:
        _override.reset(token)


def is_memory_preflight(error):
    # Preserve typed admission errors through existing adapter ValueError wrappers.
    seen = set()
    while error is not None and id(error) not in seen:
        seen.add(id(error))
        if isinstance(error, MemoryError):
            return False  # Actual allocation failure is never an admission retry.
        if isinstance(error, MemoryPreflightError):
            return True
        error = error.__cause__ or error.__context__
    return False
