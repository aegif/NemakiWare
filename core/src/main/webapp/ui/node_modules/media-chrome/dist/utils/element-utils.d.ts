import type MediaController from '../media-controller.js';
export declare function namedNodeMapToObject(namedNodeMap: NamedNodeMap): {};
/**
 * Get the media controller element from the `mediacontroller` attribute or closest ancestor.
 * @param host - The element to search for the media controller.
 */
export declare function getMediaController(host: HTMLElement): MediaController | undefined;
/**
 * Get the media controller element from the `mediacontroller` attribute.
 * @param host - The element to search for the media controller.
 * @return
 */
export declare function getAttributeMediaController(host: HTMLElement): MediaController | undefined;
export declare const updateIconText: (svg: HTMLElement, value: string, selector?: string) => void;
export declare const getAllSlotted: (el: HTMLElement, name: string) => HTMLCollection | HTMLElement[];
export declare const getSlotted: (el: HTMLElement, name: string) => HTMLElement;
/**
 *
 * @param {{ contains?: Node['contains'] }} [rootNode]
 * @param {Node} [childNode]
 * @returns boolean
 */
export declare const containsComposedNode: (rootNode: Node, childNode: Node) => boolean;
export declare const closestComposedNode: <T extends Element = Element>(childNode: Element, selector: string) => T;
/**
 * Get the active element, accounting for Shadow DOM subtrees.
 * @param root - The root node to search for the active element.
 */
export declare function getActiveElement(root?: Document | ShadowRoot): HTMLElement;
/**
 * Gets the document or shadow root of a node, not the node itself which can lead to bugs.
 * https://developer.mozilla.org/en-US/docs/Web/API/Node/getRootNode#return_value
 * @param node - The node to get the root node from.
 */
export declare function getDocumentOrShadowRoot(node: Node): Document | ShadowRoot | null;
/**
 * Checks if the element is visible includes opacity: 0 and visibility: hidden.
 * @param element - The element to check for visibility.
 */
export declare function isElementVisible(element: HTMLElement, { depth, checkOpacity, checkVisibilityCSS }?: {
    depth?: number;
    checkOpacity?: boolean;
    checkVisibilityCSS?: boolean;
}): boolean;
export type Point = {
    x: number;
    y: number;
};
/**
 * Get progress ratio of a point on a line segment.
 * @param x - The x coordinate of the point.
 * @param y - The y coordinate of the point.
 * @param p1 - The first point of the line segment.
 * @param p2 - The second point of the line segment.
 */
export declare function getPointProgressOnLine(x: number, y: number, p1: Point, p2: Point): number;
export declare function distance(p1: Point, p2: Point): number;
/**
 * Get or insert a CSSStyleRule with a selector in an element containing <style> tags.
 * @param styleParent - The parent element containing <style> tags.
 * @param selectorText - The selector text of the CSS rule.
 * @return {CSSStyleRule | {
 *   style: {
 *     setProperty: () => void,
 *     removeProperty: () => void,
 *     width?: string,
 *     height?: string,
 *     display?: string,
 *     transform?: string,
 *   },
 *   selectorText: string,
 * }}
 */
export declare function getOrInsertCSSRule(styleParent: Element | ShadowRoot, selectorText: string): CSSStyleRule;
/**
 * Get a CSSStyleRule with a selector in an element containing <style> tags.
 * @param  styleParent - The parent element containing <style> tags.
 * @param  predicate - A function that returns true for the desired CSSStyleRule.
 */
export declare function getCSSRule(styleParent: Element | ShadowRoot, predicate: (selectorText: string) => boolean): CSSStyleRule | undefined;
/**
 * Insert a CSSStyleRule with a selector in an element containing <style> tags.
 * @param styleParent - The parent element containing <style> tags.
 * @param selectorText - The selector text of the CSS rule.
 */
export declare function insertCSSRule(styleParent: Element | ShadowRoot, selectorText: string): CSSStyleRule | undefined;
/**
 * Gets the number represented by the attribute
 * @param el - (Should be an HTMLElement, but need any for SSR cases)
 * @param attrName - The name of the attribute to get
 * @param defaultValue - The default value to return if the attribute is not set
 * @returns Will return undefined if no attribute set
 */
export declare function getNumericAttr(el: HTMLElement, attrName: string, defaultValue?: number): number | undefined;
/**
 * @param el - (Should be an HTMLElement, but need any for SSR cases)
 * @param attrName - The name of the attribute to set
 * @param value - The value to set
 */
export declare function setNumericAttr(el: HTMLElement, attrName: string, value: number): void;
/**
 * @param el - (Should be an HTMLElement, but need any for SSR cases)
 * @param attrName - The name of the attribute to get
 */
export declare function getBooleanAttr(el: HTMLElement, attrName: string): boolean;
/**
 * @param el - (Should be an HTMLElement, but need any for SSR cases)
 * @param attrName - The name of the attribute to set
 * @param value - The value to set
 */
export declare function setBooleanAttr(el: HTMLElement, attrName: string, value: boolean): void;
/**
 * @param el - (Should be an HTMLElement, but need any for SSR cases)
 * @param attrName - The name of the attribute to get
 * @param defaultValue - The default value to return if the attribute is not set
 */
export declare function getStringAttr(el: HTMLElement, attrName: string, defaultValue?: any): any;
/**
 * @param el -  (Should be an HTMLElement, but need any for SSR cases)
 * @param attrName - The name of the attribute to get
 * @param value - The value to set
 */
export declare function setStringAttr(el: HTMLElement, attrName: string, value: string): void;
