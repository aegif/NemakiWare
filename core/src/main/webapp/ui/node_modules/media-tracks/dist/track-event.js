class TrackEvent extends Event {
  track;
  constructor(type, init) {
    super(type);
    this.track = init.track;
  }
}
export {
  TrackEvent
};
