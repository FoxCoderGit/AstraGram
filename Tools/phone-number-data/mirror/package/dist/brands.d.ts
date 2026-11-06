export type BrandId = 'mts' | 'megafon' | 'beeline' | 't2' | 'yota' | 't-mobile' | 'sber-mobile' | 'alfa-mobile' | 'vtb-mobile' | 'motiv' | 'win-mobile' | 'volna' | 'rostelecom' | 'mtt';
export interface Brand {
    readonly id: BrandId;
    readonly name: string;
}
export declare const BRANDS: Readonly<Record<string, Brand>>;
//# sourceMappingURL=brands.d.ts.map