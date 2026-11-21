// SPDX-License-Identifier: MPL-2.0
import { publishStreamSelection } from "resource://gre/modules/GeckoViewStartup.sys.mjs";

export class StreamSelectionParent extends JSWindowActorParent {
  receiveMessage({ name, data }) {
    if (name === "Selection" && this.manager.isCurrentGlobal && this.manager.isActiveInTab) {
      publishStreamSelection(this.manager, data);
    }
  }
}
