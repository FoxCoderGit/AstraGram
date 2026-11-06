cmake_minimum_required(VERSION 3.16)
project(ghostkombat C)
set(CMAKE_C_STANDARD 90)
add_library(ghostkombat SHARED
 "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable_callbacks.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/0_mame2003.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/mame2003/core_options.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/mame2003/video.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/3_artwork.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/bootstrap.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/cheat.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/6_common.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/config.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/controls.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/9_cpuexec.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/10_cpuint.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/11_cpuintrf.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/datafile.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/13_drawgfx.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/14_fileio.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/harddisk.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/hash.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/17_hiscore.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/info.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/19_inptport.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/input.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/21_mame-memory.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/22_mame.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/mamedbg.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/mame_unzip.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/25_ost_samples.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/26_palette.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/png.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/profiler.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/29_sndintrf.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/30_state.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/tilemap.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/32_timer.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/ui_text.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/34_usrintrf.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/window.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/x86drc.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/37_fd1089.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/38_6522via.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/39_6532riot.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/40_6821pia.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/41_74123.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/42_74148.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/43_74153.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/44_7474.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/45_8255ppi.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/46_eeprom.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/47_idectrl.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/48_intelfsh.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/49_ds2404.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/50_ataflash.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine/mathbox.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/52_mb87078.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine/random.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/54_ticket.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/55_tmp68301.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/56_z80fmly.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/57_filter.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/58_mixer.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/59_streams.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/60_votrax.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/sound/wavwrite.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/62_avgdvg_vidhrdw.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/63_generic.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/vidhrdw/matrix3d.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/vidhrdw/poly.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/66_tlc34076.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/67_vector.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/vidhrdw/resnet.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/69_midyunit_machine_state.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/70_midyunit_video_state.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/71_williams_sndhrdw.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/72_m6809.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/73_tms34010.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/cpu/tms34010/34010fld.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/75_samples.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/76_dac.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/77_2151intf.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/78_ym2151.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/79_adpcm.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/80_hc55516.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/mame-chd/mame_sha1.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/libchdr/src/libchdr_bitstream.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/libchdr/src/libchdr_cdrom.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/libchdr/src/libchdr_chd.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/libchdr/src/libchdr_flac.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/libchdr/src/libchdr_huffman.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/libchdr/deps/lzma/LzFind.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/libchdr/deps/lzma/LzmaDec.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/libchdr/deps/lzma/LzmaEnc.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-deps/libFLAC/bitreader.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-deps/libFLAC/cpu.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-deps/libFLAC/crc.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-deps/libFLAC/fixed.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-deps/libFLAC/float.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-deps/libFLAC/format.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-deps/libFLAC/lpc.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-deps/libFLAC/md5.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-deps/libFLAC/memory.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-deps/libFLAC/stream_decoder.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-deps/libFLAC/window.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/zlib/adler32.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/zlib/crc32.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/zlib/deflate.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/zlib/inffast.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/zlib/inflate.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/zlib/inftrees.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/zlib/ioapi.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/zlib/trees.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/zlib/uncompr.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/zlib/unzip.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/zlib/zutil.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/sljit/sljit_mame.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/compat/compat_posix_string.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/compat/compat_snprintf.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/compat/compat_strcasestr.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/compat/compat_strl.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/compat/fopen_utf8.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/encodings/encoding_crc32.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/encodings/encoding_utf.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/file/file_path.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/file/file_path_io.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/streams/file_stream.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/streams/file_stream_transforms.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/streams/interface_stream.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/streams/memory_stream.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/streams/rzip_stream.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/streams/stdin_stream.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/streams/trans_stream.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/streams/trans_stream_pipe.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/streams/trans_stream_zlib.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/string/stdstring.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/utils/md5.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/vfs/vfs_implementation.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/time/rtime.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/ghost_driver.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/midyunit_selected.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade/bridge.c"
  "C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade/unrelated_platforms.c"
)
target_include_directories(ghostkombat PRIVATE
 "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus"
 "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src"
 "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/includes"
 "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/cpu/m68000"
 "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/mame2003"
 "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-common/include"
 "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/libretro-deps/libFLAC/include"
 "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/mame-chd"
 "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/lib/zlib"
 "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine"
 "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/vidhrdw"
 "C:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/sndhrdw"
)
target_compile_definitions(ghostkombat PRIVATE "__LIBRETRO__" "PI=3.1415927" "M_PI=3.1415927" "HAS_ADSP2100=0" "HAS_ADSP2101=0" "HAS_ADSP2104=0" "HAS_ADSP2105=0" "HAS_ADSP2115=0" "HAS_ADSP2181=0" "HAS_Z80=0" "HAS_Z180=0" "HAS_8080=0" "HAS_8085A=0" "HAS_M6502=0" "HAS_M65C02=0" "HAS_M65SC02=0" "HAS_M65CE02=0" "HAS_M6509=0" "HAS_M6510=0" "HAS_M6510T=0" "HAS_M7501=0" "HAS_M8502=0" "HAS_N2A03=0" "HAS_DECO16=0" "HAS_M4510=0" "HAS_H6280=0" "HAS_I86=0" "HAS_I88=0" "HAS_I186=0" "HAS_I188=0" "HAS_I286=0" "HAS_V20=0" "HAS_V30=0" "HAS_V33=0" "HAS_V60=0" "HAS_V70=0" "HAS_I8035=0" "HAS_I8039=0" "HAS_I8048=0" "HAS_N7751=0" "HAS_I8X41=0" "HAS_I8051=0" "HAS_M6800=0" "HAS_M6801=0" "HAS_M6802=0" "HAS_M6803=0" "HAS_M6808=0" "HAS_HD63701=0" "HAS_NSC8105=0" "HAS_M6805=0" "HAS_M68705=0" "HAS_HD63705=0" "HAS_HD6309=0" "HAS_M6809=1" "HAS_KONAMI=0" "HAS_M68000=0" "HAS_M68010=0" "HAS_M68EC020=0" "HAS_M68020=0" "HAS_T11=0" "HAS_S2650=0" "HAS_TMS34010=1" "HAS_TMS34020=0" "HAS_TMS9900=0" "HAS_TMS9940=0" "HAS_TMS9980=0" "HAS_TMS9985=0" "HAS_TMS9989=0" "HAS_TMS9995=0" "HAS_TI990_10=0" "HAS_TMS99105A=0" "HAS_TMS99110A=0" "HAS_Z8000=0" "HAS_TMS32010=0" "HAS_TMS32025=0" "HAS_MB86233=0" "HAS_MB86235=0" "HAS_ADSP21062=0" "HAS_I960=0" "HAS_TMS32031=0" "HAS_CCPU=0" "HAS_PSXCPU=0" "HAS_H83002=0" "HAS_I386=0" "HAS_MN10200=0" "HAS_M37702=0" "HAS_ASAP=0" "HAS_UPD7810=0" "HAS_UPD7807=0" "HAS_ARM=0" "HAS_ARM7=0" "HAS_JAGUAR=0" "HAS_R3000=0" "HAS_R4600=0" "HAS_R5000=0" "HAS_SH2=0" "HAS_SH3=0" "HAS_DSP32C=0" "HAS_PIC16C54=0" "HAS_PIC16C55=0" "HAS_PIC16C56=0" "HAS_PIC16C57=0" "HAS_PIC16C58=0" "HAS_G65816=0" "HAS_SPC700=0" "HAS_E132XS=0" "HAS_CUSTOM=0" "HAS_SAMPLES=1" "HAS_DAC=1" "HAS_DISCRETE=0" "HAS_AY8910=0" "HAS_YM2203=0" "HAS_YM2151=0" "HAS_YM2151_ALT=1" "HAS_YM2608=0" "HAS_YM2610=0" "HAS_YM2610B=0" "HAS_YM2612=0" "HAS_YM3438=0" "HAS_YM2413=0" "HAS_YM3812=0" "HAS_YM3526=0" "HAS_YMZ280B=0" "HAS_Y8950=0" "HAS_SN76477=0" "HAS_SN76496=0" "HAS_POKEY=0" "HAS_TIA=0" "HAS_NES=0" "HAS_ASTROCADE=0" "HAS_NAMCO=0" "HAS_NAMCO_15XX=0" "HAS_NAMCO_52XX=0" "HAS_NAMCO_54XX=0" "HAS_NAMCONA=0" "HAS_TMS36XX=0" "HAS_TMS5110=0" "HAS_TMS5220=0" "HAS_VLM5030=0" "HAS_ADPCM=0" "HAS_OKIM6295=1" "HAS_MSM5205=0" "HAS_MSM5232=0" "HAS_UPD7759=0" "HAS_HC55516=1" "HAS_K005289=0" "HAS_K007232=0" "HAS_K051649=0" "HAS_K053260=0" "HAS_K054539=0" "HAS_SEGAPCM=0" "HAS_RF5C68=0" "HAS_CEM3394=0" "HAS_C140=0" "HAS_C352=0" "HAS_QSOUND=0" "HAS_SAA1099=0" "HAS_IREMGA20=0" "HAS_ES5505=0" "HAS_ES5506=0" "HAS_BSMT2000=0" "HAS_YMF262=0" "HAS_YMF278B=0" "HAS_GAELCO_CG1V=0" "HAS_GAELCO_GAE1=0" "HAS_X1_010=0" "HAS_MULTIPCM=0" "HAS_C6280=0" "HAS_SP0250=0" "HAS_SCSP=0" "HAS_PSXSPU=0" "HAS_YMF271=0" "HAS_ICS2115=0" "ANDROID" "SPLIT_CORE" "HAVE_ZLIB" "LSB_FIRST" "ALIGN_INTS" "ALIGN_SHORTS")
target_compile_options(ghostkombat PRIVATE -O2 -fPIC -fvisibility=hidden -ffp-contract=off -fsigned-char -ffunction-sections -fdata-sections -Wno-implicit-function-declaration -Wno-incompatible-pointer-types -Wno-int-conversion)
target_link_options(ghostkombat PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
target_link_libraries(ghostkombat m log)

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/0_mame2003.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/mame2003")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/3_artwork.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/6_common.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/9_cpuexec.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/10_cpuint.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/11_cpuintrf.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/13_drawgfx.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/14_fileio.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/17_hiscore.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/19_inptport.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/21_mame-memory.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/22_mame.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/25_ost_samples.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/26_palette.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/29_sndintrf.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/30_state.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/32_timer.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/34_usrintrf.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/37_fd1089.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/38_6522via.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/39_6532riot.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/40_6821pia.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/41_74123.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/42_74148.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/43_74153.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/44_7474.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/45_8255ppi.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/46_eeprom.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/47_idectrl.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/48_intelfsh.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/49_ds2404.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/50_ataflash.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/52_mb87078.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/54_ticket.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/55_tmp68301.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/56_z80fmly.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/machine")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/57_filter.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/sound")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/58_mixer.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/sound")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/59_streams.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/sound")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/60_votrax.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/sound")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/62_avgdvg_vidhrdw.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/vidhrdw")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/63_generic.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/vidhrdw")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/66_tlc34076.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/vidhrdw")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/67_vector.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/vidhrdw")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/69_midyunit_machine_state.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/70_midyunit_video_state.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/71_williams_sndhrdw.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/sndhrdw")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/72_m6809.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/cpu/m6809")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/73_tms34010.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/cpu/tms34010")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/75_samples.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/sound")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/76_dac.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/sound")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/77_2151intf.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/sound")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/78_ym2151.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/sound")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/79_adpcm.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/sound")

set_source_files_properties("C:/Users/vrfox/Desktop/telegram/Tools/kombat/arcade-build/portable/80_hc55516.c" PROPERTIES COMPILE_OPTIONS "-IC:/Users/vrfox/Desktop/telegram/Tools/kombat/vendor/mame2003-plus/src/sound")
