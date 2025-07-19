var __accessCheck = (obj, member, msg) => {
  if (!member.has(obj))
    throw TypeError("Cannot " + msg);
};
var __privateGet = (obj, member, getter) => {
  __accessCheck(obj, member, "read from private field");
  return getter ? getter.call(obj) : member.get(obj);
};
var __privateAdd = (obj, member, value) => {
  if (member.has(obj))
    throw TypeError("Cannot add the same private member more than once");
  member instanceof WeakSet ? member.add(obj) : member.set(obj, value);
};
var __privateSet = (obj, member, value, setter) => {
  __accessCheck(obj, member, "write to private field");
  setter ? setter.call(obj, value) : member.set(obj, value);
  return value;
};
var __privateWrapper = (obj, member, setter, getter) => ({
  set _(value) {
    __privateSet(obj, member, value, setter);
  },
  get _() {
    return __privateGet(obj, member, getter);
  }
});
var _range, _startTime, _previousTime, _deltaTime, _frameCount, _updateTimestamp, _updateStartValue, _lastRangeIncrease, _id, _animate;
class RangeAnimation {
  constructor(range, callback, fps) {
    __privateAdd(this, _range, void 0);
    __privateAdd(this, _startTime, void 0);
    __privateAdd(this, _previousTime, void 0);
    __privateAdd(this, _deltaTime, void 0);
    __privateAdd(this, _frameCount, void 0);
    __privateAdd(this, _updateTimestamp, void 0);
    __privateAdd(this, _updateStartValue, void 0);
    __privateAdd(this, _lastRangeIncrease, void 0);
    __privateAdd(this, _id, 0);
    __privateAdd(this, _animate, (now = performance.now()) => {
      __privateSet(this, _id, requestAnimationFrame(__privateGet(this, _animate)));
      __privateSet(this, _deltaTime, performance.now() - __privateGet(this, _previousTime));
      const fpsInterval = 1e3 / this.fps;
      if (__privateGet(this, _deltaTime) > fpsInterval) {
        __privateSet(this, _previousTime, now - __privateGet(this, _deltaTime) % fpsInterval);
        const fps = 1e3 / ((now - __privateGet(this, _startTime)) / ++__privateWrapper(this, _frameCount)._);
        const delta = (now - __privateGet(this, _updateTimestamp)) / 1e3 / this.duration;
        let value = __privateGet(this, _updateStartValue) + delta * this.playbackRate;
        const increase = value - __privateGet(this, _range).valueAsNumber;
        if (increase > 0) {
          __privateSet(this, _lastRangeIncrease, this.playbackRate / this.duration / fps);
        } else {
          __privateSet(this, _lastRangeIncrease, 0.995 * __privateGet(this, _lastRangeIncrease));
          value = __privateGet(this, _range).valueAsNumber + __privateGet(this, _lastRangeIncrease);
        }
        this.callback(value);
      }
    });
    __privateSet(this, _range, range);
    this.callback = callback;
    this.fps = fps;
  }
  start() {
    if (__privateGet(this, _id) !== 0)
      return;
    __privateSet(this, _previousTime, performance.now());
    __privateSet(this, _startTime, __privateGet(this, _previousTime));
    __privateSet(this, _frameCount, 0);
    __privateGet(this, _animate).call(this);
  }
  stop() {
    if (__privateGet(this, _id) === 0)
      return;
    cancelAnimationFrame(__privateGet(this, _id));
    __privateSet(this, _id, 0);
  }
  update({ start, duration, playbackRate }) {
    const increase = start - __privateGet(this, _range).valueAsNumber;
    const durationDelta = Math.abs(duration - this.duration);
    if (increase > 0 || increase < -0.03 || durationDelta >= 0.5) {
      this.callback(start);
    }
    __privateSet(this, _updateStartValue, start);
    __privateSet(this, _updateTimestamp, performance.now());
    this.duration = duration;
    this.playbackRate = playbackRate;
  }
}
_range = new WeakMap();
_startTime = new WeakMap();
_previousTime = new WeakMap();
_deltaTime = new WeakMap();
_frameCount = new WeakMap();
_updateTimestamp = new WeakMap();
_updateStartValue = new WeakMap();
_lastRangeIncrease = new WeakMap();
_id = new WeakMap();
_animate = new WeakMap();
export {
  RangeAnimation
};
