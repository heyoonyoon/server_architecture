import ctypes
import sys

LIB = "/Applications/UTM.app/Contents/Frameworks/qemu-img.framework/qemu-img"

lib = ctypes.CDLL(LIB)
args = ["qemu-img"] + sys.argv[1:]
argv = (ctypes.c_char_p * (len(args) + 1))(*[a.encode() for a in args], None)
lib.main.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_char_p)]
lib.main.restype = ctypes.c_int
sys.exit(lib.main(len(args), argv))
