"""Bounded truthful frame telemetry, independent of host batch size."""
import time


class FrameProgress:
    def __init__(self, callback, total, style, started, *, clock=None, interval=.2):
        self.callback, self.total, self.style = callback, total, style
        self.started, self.clock = started, clock or time.perf_counter
        self.interval, self.last_at, self.last_count = interval, started, -1
        self.completed = 0

    def update(self, completed, force=False):
        if type(completed) is not int or completed < 0 or (self.total > 0 and completed > self.total):
            raise ValueError('Invalid actual frame count')
        # Computation can lead the writer within a batch; never regress or invent work.
        self.completed = max(self.completed, completed)
        if not self.callback or self.completed <= self.last_count:
            return
        now = self.clock()
        if not force and self.last_count >= 0 and now - self.last_at < self.interval:
            return
        elapsed = max(0., now - self.started)
        fps = self.completed / elapsed if elapsed else 0.
        self.callback({'phase': 'grading', 'processedFrames': self.completed,
                       'totalFrames': self.total, 'percent': round(self.completed * 100 / max(1, self.total), 1),
                       'fps': round(fps, 1),
                       'etaSeconds': round(max(0, self.total - self.completed) / fps, 1) if fps else 0,
                       'style': self.style})
        self.last_at, self.last_count = now, self.completed
