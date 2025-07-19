/**
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 *
 */
export declare function useSyncExternalStoreWithSelector<Snapshot, Selection>(subscribe: (onStoreChange: () => void) => () => void, getSnapshot: () => Snapshot, getServerSnapshot: undefined | null | (() => Snapshot), selector: (snapshot: Snapshot) => Selection, isEqual?: (a: Selection, b: Selection) => boolean): Selection;
