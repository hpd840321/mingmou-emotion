#!/usr/bin/env python3
"""Probe face_server gRPC to determine actual feature dimensionality."""
import grpc, struct, sys
sys.path.insert(0, '/tmp/proto_out')
from inference_pb2 import FaceAnalysisRequest
from inference_pb2_grpc import FaceServiceStub

IMG = '/media/zebra/data/官渡一中初一班-0526/data/官渡一中/初一班/2026-0521/第1节/20260521074002_T55_0005A80D.jpg'

with open(IMG, 'rb') as f:
    img = f.read()

ch = grpc.insecure_channel('localhost:50053',
    options=[('grpc.max_send_message_length', 50*1024*1024),
             ('grpc.max_receive_message_length', 50*1024*1024)])
stub = FaceServiceStub(ch)

# Try with FEAT_RECOGNITION (0x02) — same as step3
req = FaceAnalysisRequest(image_data=img, enabled_features=0x01 | 0x02 | 0x20)
resp = stub.Analyze(req, timeout=30)

print(f'Faces detected: {len(resp.faces)}')
for i, face in enumerate(resp.faces[:5]):
    fb = face.feature
    if fb:
        n_floats = len(fb) // 4
        print(f'  Face {i}: raw_bytes={len(fb)}, dims={n_floats}')
        if n_floats >= 512:
            floats = struct.unpack(f'{n_floats}f', fb)
            print(f'  Face {i}: first 5 = {[round(x,4) for x in floats[:5]]}')
            print(f'  Face {i}: last  5 = {[round(x,4) for x in floats[-5:]]}')
            print(f'  >>> WARNING: step3.py only unpacked 128 of {n_floats} dims! <<<')
        elif n_floats >= 128:
            floats = struct.unpack(f'{n_floats}f', fb)
            print(f'  Face {i}: first 5 = {[round(x,4) for x in floats[:5]]}')
            print(f'  Face {i}: is 128-dim (step3 used full dimension)')
        else:
            print(f'  Face {i}: UNEXPECTED dimension {n_floats}')
    else:
        print(f'  Face {i}: NO FEATURE')

ch.close()
