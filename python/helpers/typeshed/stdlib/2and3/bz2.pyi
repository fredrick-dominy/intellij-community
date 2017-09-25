# Stubs for bz2

from typing import Any, BinaryIO, TextIO, IO, Optional, Union

def compress(data: bytes, compresslevel: int = ...) -> bytes: ...
def decompress(data: bytes) -> bytes: ...

def open(filename: Union[str, bytes, IO[Any]],
         mode: str = 'rb',
         encoding: Optional[str] = None,
         errors: Optional[str] = None,
         newline: Optional[str] = None) -> IO[Any]: ...

class BZ2File(BinaryIO):
    def __init__(self,
                 filename: Union[str, bytes, IO[Any]],
                 mode: str = "r",
                 buffering: Optional[Any] = None,
                 compresslevel: int = 9) -> None: ...

class BZ2Compressor(object):
    def __init__(self, compresslevel: int = 9) -> None: ...
    def compress(self, data: bytes) -> bytes: ...
    def flush(self) -> bytes: ...

class BZ2Decompressor(object):
    def decompress(self, data: bytes) -> bytes: ...
    @property
    def eof(self) -> bool: ...
    @property
    def needs_input(self) -> bool: ...
    @property
    def unused_data(self) -> bytes: ...
