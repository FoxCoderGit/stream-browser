// SPDX-License-Identifier: MPL-2.0
export class StreamSelectionChild extends JSWindowActorChild {
  #active = null;
  #revision = 0;

  handleEvent(event) {
    const media = event.target;
    if (!event.isTrusted || !(media instanceof this.contentWindow.HTMLMediaElement)) {
      return;
    }

    const activating = event.type === "play" || event.type === "playing";
    if (activating) {
      if (this.#active !== media) {
        this.#active = media;
        this.#revision++;
      }
    } else if (this.#active !== media) {
      return;
    }

    const changing = event.type === "emptied" || event.type === "loadstart";
    if (changing) {
      this.#revision++;
    }
    this.sendAsyncMessage("Selection", {
      revision: this.#revision,
      activating,
      source: changing ? "" : media.currentSrc,
      width: changing ? 0 : (media.videoWidth ?? 0),
      height: changing ? 0 : (media.videoHeight ?? 0),
      duration: changing || !Number.isFinite(media.duration) ? -1 : media.duration,
      audio: media.localName === "audio",
    });
  }
}
