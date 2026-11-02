# Tradutor ZH→PT 2.0 (offline, qualidade máxima)

Tradução simultânea de **mandarim falado → português do Brasil** no Android, sem dados móveis.
Otimizado para o **Samsung Galaxy A55** (Exynos 1480, 8 GB).

## Arquitetura
| Etapa | Modelo | Papel |
|---|---|---|
| 1. Ouvir (instantâneo) | Zipformer streaming zh (sherpa-onnx, int8) | legenda em chinês enquanto a pessoa fala |
| 2. Reconhecer a frase | SenseVoice 2025 (sherpa-onnx, int8) | releitura da frase inteira, mais precisa |
| 3a. Legenda rápida | Google ML Kit | português aproximado em tempo real (≈) |
| 3b. Tradução final | **Tencent HY-MT1.5-1.8B** (llama.cpp, Q4_K_M) | modelo especializado em tradução do chinês; substitui a legenda rápida com a versão final |

Tudo roda no celular. O HY-MT recebe as duas frases anteriores como contexto, para manter pronomes e termos coerentes.
Se o orador falar rápido demais e a fila do HY-MT passar de 3 frases, as frases novas ficam com a tradução do ML Kit, para a legenda não atrasar.

## Compilar (GitHub Actions)
1. Crie um repositório no GitHub e envie todo o conteúdo desta pasta (incluindo `.github`).
2. **Actions → Compilar APK** (roda a cada push ou por *Run workflow*). Leva ~15–25 min (compila o llama.cpp).
3. Baixe o artefato **TradutorZH-PT-apk** e instale o `.apk` (~450 MB, já com os modelos de voz).

## Primeiro uso — uma vez, no Wi-Fi
1. Permita o microfone.
2. **Baixar legenda rápida (ML Kit)**: ~60 MB.
3. **Baixar tradutor HY-MT**: ~1,1 GB. Só baixa no Wi-Fi: se o Wi-Fi cair, o download para e continua de onde parou.
   Alternativa: baixe no PC o arquivo `HY-MT1.5-1.8B-Q4_K_M.gguf` (huggingface.co/tencent/HY-MT1.5-1.8B-GGUF),
   passe para o celular e toque em **Importar .gguf**.
4. Depois disso, pode usar em modo avião.

## Ajustes (botão "Ajustes")
- **Reconhecimento de voz (recomendado)**: sinal limpo, sem AGC agressivo.
- **Câmera – mic traseiro**: aponte o celular para quem fala (palestra, reunião).
- **Bruto / Comunicação / Padrão**: alternativas para ambientes específicos.
- **Microfone / entrada**: mic interno inferior/traseiro, fone com fio ou **lapela USB-C**. A lapela dá o melhor resultado com orador distante.
- **Ganho digital** 1x–4x, com medidor de nível (o ideal é o meio da barra).
- **Redutor de ruído**, **Falar tradução** (use com fones; o app pausa a escuta enquanto fala) e **HY-MT liga/desliga**.

Áudio: 16 kHz mono, blocos de 100 ms, thread prioritária, filtro passa-altas ~80 Hz, AGC do sistema desligado,
fim de frase detectado após 0,8 s de silêncio (máx. 15 s por frase).

## Latência esperada no A55
- Chinês na tela: imediato. Legenda ≈ em português: ~0,5 s.
- Frase final do HY-MT: ~2–5 s depois de a pessoa terminar a frase (depende do tamanho).

## Versões fixadas
sherpa-onnx 2.0.1 · llama.cpp b12059 · ML Kit Translate 17.0.3 · AGP 8.5.2 · Kotlin 1.9.24 · NDK 27.2
