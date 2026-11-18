"""Execution scheduling only: no resolution, precision or asset changes."""
from contextlib import contextmanager
from functools import wraps
from config import get_current_config


def execution_mode(config=None):
    cfg = get_current_config() if config is None else config
    value = cfg.get('execution_mode', 'concurrent')
    if not isinstance(value, str) or value not in ('serial', 'concurrent'):
        raise ValueError('执行模式须为 serial 或 concurrent')
    return value


def parallel_enabled(config=None):
    return execution_mode(config) == 'concurrent'


@contextmanager
def execution_scope(config=None):
    from legend.leica_all_colors import execution_parallel_scope
    with execution_parallel_scope(parallel_enabled(config)):
        yield


def scoped_execution(function):
    @wraps(function)
    def call(*args, **kwargs):
        with execution_scope():
            return function(*args, **kwargs)
    return call
