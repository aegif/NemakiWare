class RenditionEvent extends Event {
  rendition;
  constructor(type, init) {
    super(type);
    this.rendition = init.rendition;
  }
}
export {
  RenditionEvent
};
