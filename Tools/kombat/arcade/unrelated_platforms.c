/* Generic MAME UI references Neo Geo card services even in a Midway-only build.
 * MK1 has no such hardware; keep those unrelated UI operations unavailable. */
int mcd_action=0,mcd_number=0,memcard_status=0,memcard_number=-1;
int neogeo_memcard_load(int number){return 0;}
int neogeo_memcard_create(int number){return 0;}
void neogeo_memcard_eject(void){}
