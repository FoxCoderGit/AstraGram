import { BRANDS } from './brands.js';
const decode = (dataset) => {
    const entries = dataset.ranges.split(';');
    const starts = new Float64Array(entries.length);
    const ends = new Float64Array(entries.length);
    const operators = new Uint16Array(entries.length);
    const places = new Uint16Array(entries.length);
    let previous = -1;
    entries.forEach((entry, index) => {
        const [gap = 0, length = 0, operator = 0, place = 0] = entry.split(',').map((value) => parseInt(value, 36));
        starts[index] = previous + 1 + gap;
        ends[index] = previous = previous + 1 + gap + length;
        operators[index] = operator;
        places[index] = place;
    });
    return {
        type: dataset.type,
        starts,
        ends,
        operators,
        places,
        operatorList: dataset.operators.map(([inn, name]) => ({ name, inn, brand: BRANDS[inn] ?? null })),
        placeList: dataset.places.map((value) => {
            const [first = '', second] = value.split('|');
            return second === undefined ? [first, null] : [second, first];
        }),
    };
};
const find = (table, value) => {
    let low = 0;
    let high = table.starts.length - 1;
    while (low <= high) {
        const middle = (low + high) >> 1;
        if ((table.ends[middle] ?? 0) < value)
            low = middle + 1;
        else if ((table.starts[middle] ?? 0) > value)
            high = middle - 1;
        else
            return middle;
    }
    return -1;
};
const SEPARATORS = /[\s()\-. ]/g;
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
export const parsePhone = (input) => {
    const compact = input.trim().replace(SEPARATORS, '');
    const match = /^(?:\+7|7|8)?(\d{10})$/.exec(compact);
    if (!match?.[1] || (compact.startsWith('+') && !compact.startsWith('+7')))
        return null;
    return /^[3489]/.test(match[1]) ? match[1] : null;
};
/**
 * Formats a Russian phone number as `+7 912 345-67-89`, or returns `null` if it is not one.
 *
 * @example
 * ```ts
 * formatPhone('89123456789'); // '+7 912 345-67-89'
 * ```
 */
export const formatPhone = (input) => {
    const national = parsePhone(input);
    return national && `+7 ${national.slice(0, 3)} ${national.slice(3, 6)}-${national.slice(6, 8)}-${national.slice(8)}`;
};
export const createLookup = (datasets) => {
    let tables = null;
    return (input) => {
        const national = parsePhone(input);
        if (!national)
            return null;
        tables ??= datasets.map(decode);
        const value = Number(national);
        for (const table of tables) {
            const index = find(table, value);
            if (index < 0)
                continue;
            const operator = table.operatorList[table.operators[index] ?? 0];
            const place = table.placeList[table.places[index] ?? 0];
            if (!operator || !place)
                return null;
            return {
                number: `+7${national}`,
                national,
                code: national.slice(0, 3),
                type: table.type,
                operator,
                region: place[0],
                locality: place[1],
            };
        }
        return null;
    };
};
//# sourceMappingURL=lookup.js.map