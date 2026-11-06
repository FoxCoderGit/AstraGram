"""Offline URL classification from the original yt-dlp extractor registry."""
def supported(url):
    from yt_dlp.extractor import gen_extractor_classes
    for extractor in gen_extractor_classes():
        if extractor.IE_NAME in ('generic', 'unsupported'):
            continue
        try:
            if extractor.suitable(url):
                return True
        except Exception:
            continue
    return False
