import logging

class Logger:
    def __init__(self, name='plugin'): self.name = str(name)
    def _write(self, level, message, *args):
        from org.telegram.messenger import FileLog
        text = str(message) % args if args else str(message)
        FileLog.d('[' + self.name + '][' + level + '] ' + text)
    def debug(self, message, *args): self._write('DEBUG', message, *args)
    def info(self, message, *args): self._write('INFO', message, *args)
    def warning(self, message, *args): self._write('WARN', message, *args)
    warn = warning
    def error(self, message, *args): self._write('ERROR', message, *args)
    def exception(self, message, *args):
        import traceback
        self.error(str(message) + '\n' + traceback.format_exc(), *args)

def get_logger(name='plugin'): return Logger(name)
