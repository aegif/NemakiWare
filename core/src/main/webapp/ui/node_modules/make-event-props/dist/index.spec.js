import { jsx as _jsx } from "react/jsx-runtime";
import { assertType, describe, expect, it, vi } from 'vitest';
import makeEventProps, { allEvents } from './index.js';
describe('makeEventProps()', () => {
    const fakeEvent = {};
    it('returns object with valid and only valid event callbacks', () => {
        const props = {
            onClick: vi.fn(),
            someInvalidProp: vi.fn(),
        };
        const result = makeEventProps(props);
        expect(result).toMatchObject({ onClick: expect.any(Function) });
    });
    it('calls getArgs function on event invoke if given', () => {
        const props = {
            onClick: vi.fn(),
            someInvalidProp: vi.fn(),
        };
        const getArgs = vi.fn();
        const result = makeEventProps(props, getArgs);
        // getArgs shall not be invoked before a given event is fired
        expect(getArgs).not.toHaveBeenCalled();
        result.onClick(fakeEvent);
        expect(getArgs).toHaveBeenCalledTimes(1);
        expect(getArgs).toHaveBeenCalledWith('onClick');
    });
    it('properly calls callbacks given in props given no getArgs function', () => {
        const props = {
            onClick: vi.fn(),
        };
        const result = makeEventProps(props);
        result.onClick(fakeEvent);
        expect(props.onClick).toHaveBeenCalledWith(fakeEvent);
    });
    it('properly calls callbacks given in props given getArgs function', () => {
        const props = {
            onClick: vi.fn(),
        };
        const getArgs = vi.fn();
        const args = {};
        getArgs.mockReturnValue(args);
        const result = makeEventProps(props, getArgs);
        result.onClick(fakeEvent);
        expect(props.onClick).toHaveBeenCalledWith(fakeEvent, args);
    });
    it('should not filter out valid event props', () => {
        const props = {
            onClick: vi.fn(),
        };
        const result = makeEventProps(props);
        assertType(result.onClick);
    });
    it('should filter out invalid event props', () => {
        const props = {
            someInvalidProp: vi.fn(),
        };
        const result = makeEventProps(props);
        expect(result).not.toHaveProperty('someInvalidProp');
    });
    it('should allow valid onClick handler to be passed', () => {
        const props = {
            onClick: (_event) => {
                // Intentionally empty
            },
        };
        // @ts-expect-no-error
        makeEventProps(props);
    });
    it('should not allow invalid onClick handler to be passed', () => {
        const props = {
            onClick: 'potato',
        };
        // @ts-expect-error-next-line
        makeEventProps(props);
    });
    it('should allow onClick handler with extra args to be passed if getArgs is provided', () => {
        const props = {
            onClick: (_event, _args) => {
                // Intentionally empty
            },
        };
        // @ts-expect-no-error
        makeEventProps(props, () => 'hello');
    });
    it('should not allow onClick handler with extra args to be passed if getArgs is not provided', () => {
        const props = {
            onClick: (_event, _args) => {
                // Intentionally empty
            },
        };
        // @ts-expect-error-next-line
        makeEventProps(props);
    });
    it('should not allow onClick handler with extra args to be passed if getArgs is provided but returns different type', () => {
        const props = {
            onClick: (_event, _args) => {
                // Intentionally empty
            },
        };
        // @ts-expect-error-next-line
        makeEventProps(props, () => 5);
    });
    it('should allow div onClick handler to be passed to div', () => {
        const props = {
            onClick: (_event) => {
                // Intentionally empty
            },
        };
        const result = makeEventProps(props);
        // @ts-expect-no-error
        // biome-ignore lint/a11y/useKeyWithClickEvents: This is only a test
        _jsx("div", { onClick: result.onClick });
    });
    it('should not allow div onClick handler to be passed to button', () => {
        const props = {
            onClick: (_event) => {
                // Intentionally empty
            },
        };
        const result = makeEventProps(props);
        // @ts-expect-error-next-line
        _jsx("button", { onClick: result.onClick, type: "submit" });
    });
    it('should allow div onClick handler with extra args to be passed to div if getArgs is provided', () => {
        const props = {
            onClick: (_event, _args) => {
                // Intentionally empty
            },
        };
        const result = makeEventProps(props, () => 'hello');
        // @ts-expect-no-error
        // biome-ignore lint/a11y/useKeyWithClickEvents: This is only a test
        _jsx("div", { onClick: result.onClick });
    });
    it('should not allow div onClick handler with extra args to be passed to button if getArgs is provided', () => {
        const props = {
            onClick: (_event, _args) => {
                // Intentionally empty
            },
        };
        const result = makeEventProps(props, () => 'hello');
        // @ts-expect-error-next-line
        _jsx("button", { onClick: result.onClick, type: "submit" });
    });
    it('should allow onClick handler with valid extra args to be passed with args explicitly typed', () => {
        const props = {
            onClick: (_event, _args) => {
                // Intentionally empty
            },
        };
        // @ts-expect-no-error
        makeEventProps(props, () => 'hello');
    });
    it('should not allow onClick handler with invalid extra args to be passed with args explicitly typed', () => {
        const props = {
            onClick: (_event, _args) => {
                // Intentionally empty
            },
        };
        // @ts-expect-error-next-line
        makeEventProps(props, () => 'hello');
    });
    it('should allow getArgs returning valid type to be passed with args explicitly typed', () => {
        const props = {};
        // @ts-expect-no-error
        makeEventProps(props, () => 'hello');
    });
    it('should not allow getArgs returning invalid type to be passed with args explicitly typed', () => {
        const props = {};
        // @ts-expect-error-next-line
        makeEventProps(props, () => 5);
    });
});
describe('allEvents', () => {
    it('should contain all events', () => {
        const sortedAllEvents = new Set([...allEvents].sort());
        expect(sortedAllEvents).toMatchSnapshot();
    });
});
