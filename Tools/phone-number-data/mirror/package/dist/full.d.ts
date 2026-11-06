import { type PhoneInfo } from './lookup.js';
export { BRANDS, type Brand, type BrandId } from './brands.js';
export { formatPhone, parsePhone, type Operator, type PhoneInfo, type PhoneType } from './lookup.js';
/** Date of the bundled registry snapshot, `YYYY-MM-DD`. */
export declare const updated: string;
/**
 * Looks up a mobile or landline phone number. Returns `null` for invalid input and unassigned ranges.
 *
 * @example
 * ```ts
 * lookup('84951234567')?.type; // 'landline'
 * ```
 */
export declare const lookup: (input: string) => PhoneInfo | null;
//# sourceMappingURL=full.d.ts.map