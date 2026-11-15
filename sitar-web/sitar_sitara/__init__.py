import os

# On Apple Silicon, run any PyTorch operation the Mac GPU (MPS) doesn't
# support yet on the CPU instead of failing. Must be set before torch loads.
os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")
