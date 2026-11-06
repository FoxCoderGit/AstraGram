import { type PhoneInfo } from './lookup.js';
export { BRANDS, type Brand, type BrandId } from './brands.js';
export { formatPhone, parsePhone, type Operator, type PhoneInfo, type PhoneType } from './lookup.js';
/** Date of the bundled registry snapshot, `YYYY-MM-DD`. */
export declare const updated: string;
/**
 * Looks up a mobile phone number. Returns `null` for invalid input, landlines and unassigned ranges.
 *
 * @example
 * ```ts
 * lookup('89123456789')?.operator.brand?.name; // 'МТС'
 * ```
 */
export declare const lookup: (input: string) => PhoneInfo | null;
//# sourceMappingURL=index.d.ts.map