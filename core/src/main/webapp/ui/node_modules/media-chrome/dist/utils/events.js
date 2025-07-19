class InvokeEvent extends Event {
  /**
   * @param init - The event options.
   */
  constructor({ action = "auto", relatedTarget, ...options }) {
    super("invoke", options);
    this.action = action;
    this.relatedTarget = relatedTarget;
  }
}
class ToggleEvent extends Event {
  /**
   * @param init - The event options.
   */
  constructor({ newState, oldState, ...options }) {
    super("toggle", options);
    this.newState = newState;
    this.oldState = oldState;
  }
}
export {
  InvokeEvent,
  ToggleEvent
};
