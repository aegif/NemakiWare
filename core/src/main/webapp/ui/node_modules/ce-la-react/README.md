# ce-la-react

Create a React component from a custom element with SSR support.

Large parts of this package are copied from the 
[`@lit/react`](https://github.com/lit/lit/tree/main/packages/react) package. 

The main difference is that this package is more geared towards 
the use of vanilla custom elements and support SSR in a simpler way.

Key differences are:

- Favor attributes over properties (for primitive values)
- Configurable conversion functions for properties to attributes
- Support for custom element templates via a static method `getTemplateHTML`

## `createComponent`

While React can render Web Components, it 
[cannot](https://custom-elements-everywhere.com/libraries/react/results/results.html)
easily pass React props to custom element properties or event listeners.

This package provides a utility wrapper `createComponent` which makes a
React component wrapper for a custom element class. The wrapper correctly
passes React `props` to properties accepted by the custom element and listens
for events dispatched by the custom element.

Since React v19 there is better support for custom elements, but unfortunately
React wrappers are still needed for the time being.

## How it works

For properties, the wrapper inspects the web component class to discover
its available properties. Next it differentiates from the original `@lit/react`.

Then any React `props` passed with property names are set on the custom element 
as properties if there is no accomponying attribute name listed in 
the static `observedAttributes` array and if the property is not found in
the base class of the custom element. Primitive values are preferred as
attributes to support SSR (server side rendering).

For events, `createComponent` accepts a mapping of React event prop names
to events fired by the custom element. For example passing `{onfoo: 'foo'}`
means a function passed via a `prop` named `onfoo` will be called when the
custom element fires the foo event with the event as an argument.

## Usage

Import `React`, a custom element class, and `createComponent`.

```js
import * as React from 'react';
import { createComponent } from 'ce-la-react';
import { MyElement } from './my-element.js';

export const MyElementComponent = createComponent({
  tagName: 'my-element',
  elementClass: MyElement,
  react: React,
  events: {
    onactivate: 'activate',
    onchange: 'change',
  },
});
```

After defining the React component, you can use it just as you would any other
React component.

```jsx
<MyElementComponent
  active={isActive}
  onactivate={(e) => (isActive = e.active)}
/>
```

## Typescript

Event callback types can be refined by type casting with `EventName`. The
type cast helps `createComponent` correlate typed callbacks to property names in
the event property map.

Non-casted event names will fallback to an event type of `Event`.

```ts
import * as React from 'react';
import { createComponent } from 'ce-la-react';
import { MyElement } from './my-element.js';
import type { EventName } from 'ce-la-react';

export const MyElementComponent = createComponent({
  tagName: 'my-element',
  elementClass: MyElement,
  react: React,
  events: {
    onClick: 'pointerdown' as EventName<PointerEvent>,
    onChange: 'input',
  },
});
```

Event callbacks will match their type cast. In the example below, a
`PointerEvent` is expected in the `onClick` callback.

```tsx
<MyElementComponent
  onClick={(e: PointerEvent) => {
    console.log('DOM PointerEvent called!');
  }}
  onChange={(e: Event) => {
    console.log(e);
  }}
/>
```

NOTE: This type casting is not associated to any component property. Be
careful to use the corresponding type dispatched or bubbled from the
webcomponent. Incorrect types might result in additional properties, missing
properties, or properties of the wrong type.

## Converting `props` to attributes

For advanced use cases, the `props` to attributes conversion functions can be customized.

1. `toAttributeName` - A function that converts the prop name to an attribute
   name. This is useful when the attribute name is a different format than the prop name.
   e.g. playbackId -> playback-id
2. `toAttributeValue` - A function that converts the prop value to an attribute
   value. This could be useful for serializing complex objects to strings.

The default functions are:

```ts
export function defaultToAttributeName(propName: string) {
  return propName.toLowerCase();
}

export function defaultToAttributeValue(propValue: unknown) {
  if (typeof propValue === 'boolean') return propValue ? '' : undefined;
  if (typeof propValue === 'function') return undefined;
  if (typeof propValue === 'object' && propValue !== null) return undefined;
  return propValue;
}
```

## SSR (Server Side Rendering)

This package supports SSR by rendering the custom element template and setting
the React `dangerouslySetInnerHTML` prop with the rendered template. The custom
element is then hydrated on the client side.

The only requirement is that the custom element class must provide a
`static getTemplateHTML` method that returns the template HTML string. This
method is called with attributes that are converted from the React `props`.

```ts
import * as React from 'react';
import { createComponent } from 'ce-la-react';

class MyProfile extends (globalThis.HTMLElement ?? class {}) {
  static shadowRootOptions = { mode: 'open' };

  static getTemplateHTML(attrs: Record<string, string>) {
    return `<h1>Hello, ${attrs.firstname}!</h1>`;
  }

  static get observedAttributes() {
    return ['firstname'];
  }

  #isInit = false;

  // This init method might look strange but it is a pattern to avoid
  // trying to access attributes in the constructor which is illegal!
  // 
  // Just remember to call this method everywhere before you need to
  // evaluate anything in the element's shadow DOM. 
  // Could be even in a property getter or setter.
  #init() {
    if (this.#isInit) return;
    this.#isInit = true;

    if (!this.shadowRoot) {
      this.attachShadow({ mode: 'open' });
      this.#render();
    }

    this.#upgradeProperty('firstName');
  }

  #render() {
    if (this.shadowRoot) {
      this.shadowRoot.innerHTML = MyProfile.getTemplateHTML({
        ...namedNodeMapToObject(this.attributes),
      });
    }
  }

  // This is a pattern to update property values that are set before 
  // the custom element is upgraded.
  // https://web.dev/custom-elements-best-practices/#make-properties-lazy
  #upgradeProperty(this: ElementProps<MyProfile>, prop: string) {
    if (Object.prototype.hasOwnProperty.call(this, prop)) {
      const value = this[prop];
      // Delete the set property from this instance.
      delete this[prop];
      // Set the value again via the (prototype) setter on this class.
      this[prop] = value;
    }
  }

  connectedCallback() {
    this.#init();
  }

  attributeChangedCallback(name: string, oldValue: string, newValue: string) {
    this.#init();

    if (oldValue === newValue) return;

    if (name === 'firstname') {
      this.#render();
    }
  }

  get firstName() {
    return this.getAttribute('firstname');
  }

  set firstName(value) {
    if (value != null) this.setAttribute('firstname', value);
    else this.removeAttribute('firstname');
  }
}

if (globalThis.customElements && !globalThis.customElements.get('my-profile')) {
  globalThis.customElements.define('my-profile', MyProfile);
}

const MyProfileComponent = createComponent({
  react: React,
  tagName: 'my-profile',
  elementClass: MyProfile,
});
```
