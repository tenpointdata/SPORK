#!/usr/bin/env python3
#
# Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
# SPDX-License-Identifier: Apache-2.0
#
# Builds the S-TEXT model of the SPORK encoder sidecar (WS13) from the official Hugging Face repositories at pinned
# revisions, and writes the golden reference the Java golden test checks against.
#
#   models/gte-multilingual-base/1/model_int8.onnx   int8 weights, CLS pooled in the graph, 768-d out
#   models/gte-multilingual-base/1/tokenizer.json    the repository's tokenizer, unchanged
#   models/gte-multilingual-base/1/model.json        the manifest the registry loads (hashes, revisions, license)
#   src/test/resources/golden/gte-multilingual-base@1.json   token ids and fp32 + int8 reference embeddings
#
# Nothing here uses trust_remote_code: the modeling code is downloaded at its pinned revision and imported from that
# snapshot, so the code that runs is exactly the code whose revision the manifest records.
#
# Tested with Python 3.12, torch 2.5.1, transformers 4.46.3, onnx 1.17.0, onnxruntime 1.23.2, huggingface_hub 0.26.5.
#
# Usage: python export_gte.py [--models-dir DIR] [--cache-dir DIR] [--golden FILE] [--skip-golden]

import argparse
import hashlib
import importlib
import json
import os
import shutil
import sys
import tempfile

import numpy as np

MODEL_REPO = "Alibaba-NLP/gte-multilingual-base"
MODEL_REVISION = "9bbca17d9273fd0d03d5725c7a4b0f6b45142062"
CODE_REPO = "Alibaba-NLP/new-impl"
CODE_REVISION = "40ced75c3017eb27626c9d4ea981bde21a2662f4"

ALGO = "gte-multilingual-base"
VER = "1"
MAX_TOKENS = 512
DIMENSIONS = 256
OPSET = 17

GOLDEN_TEXTS = [
    "Sweet and discreet, new in town, available 24/7. Call me at 555-0142.",
    "Nueva en la ciudad, muy cariñosa, llámame al 555 014 2222 solo hoy",
    "Nouvelle en ville, disponible ce soir, appelez-moi",
    "新到的女孩 24小时服务 请致电",
    "Новая девушка в городе, звоните",
    "ฉันมาใหม่ในเมือง โทรหาฉันได้",
    "c4ll m3 n0w 🔥🔥 f!ve f!ve f!ve 0 1 4 2 💋 no b1@ck m3n",
    "hotel bathroom mirror selfie",
    "a",
    "The quick brown fox jumps over the lazy dog. " * 120,
]


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def import_modeling(code_dir):
    # modeling.py does "from .configuration import NewConfig": import the snapshot as a package
    package_root = tempfile.mkdtemp(prefix="gte_code_")
    package_dir = os.path.join(package_root, "gte_new_impl")
    os.makedirs(package_dir)
    for name in ("configuration.py", "modeling.py"):
        shutil.copyfile(os.path.join(code_dir, name), os.path.join(package_dir, name))
    open(os.path.join(package_dir, "__init__.py"), "w").close()
    sys.path.insert(0, package_root)
    configuration = importlib.import_module("gte_new_impl.configuration")
    modeling = importlib.import_module("gte_new_impl.modeling")
    return configuration.NewConfig, modeling.NewModel


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    module_dir = os.path.dirname(here)
    parser = argparse.ArgumentParser()
    parser.add_argument("--models-dir", default=os.path.join(module_dir, "models"))
    parser.add_argument("--cache-dir", default=None)
    parser.add_argument("--golden", default=os.path.join(module_dir, "src", "test", "resources", "golden", f"{ALGO}@{VER}.json"))
    parser.add_argument("--skip-golden", action="store_true")
    args = parser.parse_args()

    import torch
    from huggingface_hub import snapshot_download
    from onnxruntime.quantization import QuantType, quantize_dynamic
    from onnxruntime.quantization.matmul_nbits_quantizer import DefaultWeightOnlyQuantConfig, MatMulNBitsQuantizer
    import onnxruntime as ort
    from tokenizers import Tokenizer

    model_dir = snapshot_download(MODEL_REPO, revision=MODEL_REVISION, cache_dir=args.cache_dir,
                                  allow_patterns=["*.json", "model.safetensors", "1_Pooling/*"])
    code_dir = snapshot_download(CODE_REPO, revision=CODE_REVISION, cache_dir=args.cache_dir,
                                 allow_patterns=["configuration.py", "modeling.py"])
    NewConfig, NewModel = import_modeling(code_dir)

    config = NewConfig.from_pretrained(model_dir)
    # The reference path: no xformers, no unpadding, so the traced graph is plain attention with a mask
    config.use_memory_efficient_attention = False
    config.unpad_inputs = False
    model = NewModel.from_pretrained(model_dir, config=config, torch_dtype=torch.float32, add_pooling_layer=False).eval()
    # NTK scaling builds the rotary cos/sin tables for 8 x 8,192 positions; the graph would carry all 65,536 rows as
    # constants (33 MB). Rows below the model's 8,192 positions are unchanged by the cut, and the sidecar never feeds more
    rotary = model.embeddings.rotary_emb
    limit = config.max_position_embeddings
    if rotary.cos_cached.shape[0] > limit:
        rotary.cos_cached = rotary.cos_cached[:limit].clone()
        rotary.sin_cached = rotary.sin_cached[:limit].clone()
        rotary.max_seq_len_cached = limit

    class ClsPooled(torch.nn.Module):
        def __init__(self, inner):
            super().__init__()
            self.inner = inner

        def forward(self, input_ids, attention_mask):
            return self.inner(input_ids=input_ids, attention_mask=attention_mask).last_hidden_state[:, 0]

    wrapper = ClsPooled(model).eval()

    out_dir = os.path.join(args.models_dir, ALGO, VER)
    os.makedirs(out_dir, exist_ok=True)
    work = tempfile.mkdtemp(prefix="gte_export_")
    fp32_path = os.path.join(work, "model_fp32.onnx")
    int8_path = os.path.join(out_dir, "model_int8.onnx")

    tokenizer = Tokenizer.from_file(os.path.join(model_dir, "tokenizer.json"))
    tokenizer.no_padding()
    tokenizer.enable_truncation(MAX_TOKENS)

    dummy = tokenizer.encode_batch(["export trace", "a slightly longer sentence to trace with"])
    width = max(len(e.ids) for e in dummy)
    ids = torch.tensor([e.ids + [1] * (width - len(e.ids)) for e in dummy], dtype=torch.long)
    mask = torch.tensor([[1] * len(e.ids) + [0] * (width - len(e.ids)) for e in dummy], dtype=torch.long)
    with torch.no_grad():
        torch.onnx.export(wrapper, (ids, mask), fp32_path, opset_version=OPSET, do_constant_folding=True,
                          input_names=["input_ids", "attention_mask"], output_names=["cls_embedding"],
                          dynamic_axes={"input_ids": {0: "batch", 1: "sequence"}, "attention_mask": {0: "batch", 1: "sequence"},
                                        "cls_embedding": {0: "batch"}})

    # int8 in two passes (ws13-encoder.md, "Quantization"). Plain quantize_dynamic was rejected: its per-tensor activation
    # scale spans the whole batch, so a text's vector depended on its batch-mates (cosine 0.91 to itself alone), and the
    # FFN down projections' activation outliers cost accuracy (mean cosine 0.96 to fp32).
    # 1. Every MatMul: MatMulNBits, 8-bit symmetric weights in blocks of 32, int8 compute (accuracy level 4), which
    #    quantizes activations per token row, so a row never sees its batch-mates or its padding.
    nbits_path = os.path.join(work, "model_nbits.onnx")
    nbits = MatMulNBitsQuantizer(fp32_path, algo_config=DefaultWeightOnlyQuantConfig(
        bits=8, block_size=32, is_symmetric=True, accuracy_level=4, op_types_to_quantize=("MatMul",)))
    nbits.process()
    nbits.model.save_model_to_file(nbits_path, use_external_data_format=False)
    # 2. The 250,048 x 768 embedding table: weight-only int8, dequantized on lookup (768 MB fp32 to 192 MB)
    quantize_dynamic(nbits_path, int8_path, weight_type=QuantType.QInt8, op_types_to_quantize=["Gather"])
    shutil.copyfile(os.path.join(model_dir, "tokenizer.json"), os.path.join(out_dir, "tokenizer.json"))

    def embed_torch(batch_ids, batch_mask):
        with torch.no_grad():
            return wrapper(torch.tensor(batch_ids), torch.tensor(batch_mask)).numpy()

    def embed_ort(session, batch_ids, batch_mask):
        return session.run(None, {"input_ids": np.array(batch_ids, dtype=np.int64),
                                  "attention_mask": np.array(batch_mask, dtype=np.int64)})[0]

    def truncate_normalize(rows):
        rows = rows[:, :DIMENSIONS]
        return rows / np.linalg.norm(rows, axis=1, keepdims=True)

    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    fp32_session = ort.InferenceSession(fp32_path, options, providers=["CPUExecutionProvider"])
    int8_session = ort.InferenceSession(int8_path, options, providers=["CPUExecutionProvider"])

    encodings = [tokenizer.encode(t) for t in GOLDEN_TEXTS]
    items = []
    worst = {"onnx_fp32_vs_torch_max_abs": 0.0, "int8_vs_fp32_min_cosine": 1.0}
    for text, enc in zip(GOLDEN_TEXTS, encodings):
        # One text per run: the reference is free of padding effects, the Java test checks padded batches against it
        one_ids, one_mask = [enc.ids], [enc.attention_mask]
        ref = truncate_normalize(embed_torch(one_ids, one_mask))[0]
        onnx32 = truncate_normalize(embed_ort(fp32_session, one_ids, one_mask))[0]
        int8 = truncate_normalize(embed_ort(int8_session, one_ids, one_mask))[0]
        worst["onnx_fp32_vs_torch_max_abs"] = max(worst["onnx_fp32_vs_torch_max_abs"], float(np.abs(onnx32 - ref).max()))
        worst["int8_vs_fp32_min_cosine"] = min(worst["int8_vs_fp32_min_cosine"], float(np.dot(int8, ref)))
        items.append({"text": text, "ids": enc.ids, "fp32": [float(x) for x in ref], "int8": [float(x) for x in int8]})

    # A padded batch through the int8 graph must match the unpadded rows: the attention mask has to do its job
    width = max(len(e.ids) for e in encodings)
    batch_ids = [e.ids + [1] * (width - len(e.ids)) for e in encodings]
    batch_mask = [e.attention_mask + [0] * (width - len(e.ids)) for e in encodings]
    batched = truncate_normalize(embed_ort(int8_session, batch_ids, batch_mask))
    worst["int8_padded_vs_unpadded_max_abs"] = float(max(np.abs(batched[i] - np.array(items[i]["int8"])).max() for i in range(len(items))))

    manifest = {
        "algo": ALGO, "ver": VER, "space": "S-TEXT", "modality": "text",
        "file": "model_int8.onnx", "sha256": sha256(int8_path),
        "tokenizer": "tokenizer.json", "tokenizerSha256": sha256(os.path.join(out_dir, "tokenizer.json")),
        "inputIds": "input_ids", "attentionMask": "attention_mask", "output": "cls_embedding",
        "padTokenId": 1, "maxTokens": MAX_TOKENS, "nativeDimensions": 768, "dimensions": DIMENSIONS,
        "pooling": "none", "graphPooling": "cls", "normalize": True, "matryoshka": True,
        "quantization": "MatMulNBits 8-bit symmetric block 32 accuracy level 4 on every MatMul; int8 weight-only embedding Gather",
        "license": "Apache-2.0",
        "source": {"repo": MODEL_REPO, "revision": MODEL_REVISION, "codeRepo": CODE_REPO, "codeRevision": CODE_REVISION,
                   "safetensorsSha256": sha256(os.path.join(model_dir, "model.safetensors")),
                   "exporter": f"torch {torch.__version__}, onnx opset {OPSET}, onnxruntime {ort.__version__}"},
    }
    with open(os.path.join(out_dir, "model.json"), "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")

    print(json.dumps({"manifest": manifest, "checks": worst,
                      "int8_bytes": os.path.getsize(int8_path), "fp32_bytes": os.path.getsize(fp32_path)}, indent=2))

    if not args.skip_golden:
        golden = {"model": f"{ALGO}@{VER}", "sha256": manifest["sha256"], "revision": MODEL_REVISION,
                  "maxTokens": MAX_TOKENS, "dimensions": DIMENSIONS, "checks": worst, "items": items}
        os.makedirs(os.path.dirname(args.golden), exist_ok=True)
        with open(args.golden, "w") as f:
            json.dump(golden, f)
            f.write("\n")
    shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
