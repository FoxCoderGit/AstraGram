import { type Brand } from './brands.js';
export type PhoneType = 'mobile' | 'landline';
export interface Operator {
    readonly name: string;
    readonly inn: string;
    readonly brand: Brand | null;
}
export interface PhoneInfo {
    readonly number: string;
    readonly national: string;
    readonly code: string;
    readonly type: PhoneType;
    readonly operator: Operator;
    readonly region: string;
    readonly locality: string | null;
}
export interface Dataset {
    readonly type: PhoneType;
    readonly operators: readonly (readonly [inn: string, name: string])[];
    readonly places: readonly string[];
    readonly ranges: string;
}
/**
 * Normalizes a Russian phone number to its 10 national digits.
 *
 * Accepts `+7`, `7` and `8` prefixes and the usual separators: spaces, dashes, dots and parentheses.
 *
 * @example
 * ```ts
 * parsePhone('8 (912) 345-67-89'); // '9123456789'
 * parsePhone('+1 212 555 0100');   // null
 * ```
 */
export declare const parsePhone: (input: string) => string | null;
/**
 * Formats a Russian phone number as `+7 912 345-67-89`, or returns `null` if it is not one.
 *
 * @example
 * ```ts
 * formatPhone('89123456789'); // '+7 912 345-67-89'
 * ```
 */
export declare const formatPhone: (input: string) => string | null;
export declare const createLookup: (datasets: readonly Dataset[]) => ((input: string) => PhoneInfo | null);
//# sourceMappingURL=lookup.d.ts.map