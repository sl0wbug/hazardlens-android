========================================================================
HAZARDLENS TFLITE MODEL DIRECTORY
========================================================================

Place your converted `.tflite` model file inside this directory (`app/src/main/assets/`).

Supported Model File Names (in order of discovery):
1. hazard_model.tflite  (Recommended name)
2. best_float32.tflite  (Default Ultralytics YOLOv8/v11 export name)
3. best_float16.tflite
4. best.tflite
5. Any file ending with `.tflite`

Labels:
- `labels.txt` contains the class names in index order (one per line).
- Update `labels.txt` if your dataset classes or ordering differ from:
    0: Pothole
    1: Speed Breaker
    2: Manhole
    3: Road Crack
========================================================================
