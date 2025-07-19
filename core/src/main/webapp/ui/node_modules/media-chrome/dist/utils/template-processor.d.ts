import { Processor } from './template-parts.js';
export declare const processor: Processor;
export declare function tokenizeExpression(expr: string): Token[];
export declare function evaluateExpression(expr: string, state?: Record<string, any>): any;
export declare function getParamValue(raw: string, state?: Record<string, any>): any;
type Token = {
    token: string;
    type: string;
    matches?: string[];
};
export {};
