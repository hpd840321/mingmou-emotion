#!/usr/bin/env python3
import insightface, numpy as np, os
model_path = os.path.expanduser('~/.insightface/models/buffalo_l/buffalo_l/w600k_r50.onnx')
model = insightface.model_zoo.ArcFaceONNX(model_path)
model.prepare(ctx_id=-1)
dummy = np.random.randint(0, 255, (112, 112, 3), dtype=np.uint8)
emb = model.get_feat(dummy)
print(f'shape: {emb.shape}')
print(f'dims: {emb.shape[1] if len(emb.shape) > 1 else emb.shape[0]}')
if len(emb.shape) > 1:
    print(f'sample: {emb[0][:5].tolist()}')
else:
    print(f'sample: {emb[:5].tolist()}')
