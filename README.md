# Tradutor ZH→PT (offline)

Tradução simultânea de **mandarim falado → português** no Android, sem dados móveis.
Otimizado para o **Samsung Galaxy A55**.

## Como funciona
| Etapa | Tecnologia | Onde roda |
|---|---|---|
| Ouvir o mandarim | Vosk (modelo `small-cn-0.22`, embutido no APK) | 100% no celular |
| Traduzir para português | Google ML Kit Translation | 100% no celular |
| Falar a tradução (opcional) | Voz pt-BR do próprio Android | no celular |

A legenda em português aparece **enquanto a pessoa fala** (frase parcial traduzida a cada ~0,45 s)
e cada frase concluída vai para o histórico (chinês em cinza, português em negrito).

## Compilar (GitHub Actions)
1. Crie um repositório no GitHub e envie todo o conteúdo desta pasta (incluindo `.github`).
2. Vá em **Actions → Compilar APK** (roda sozinho a cada push, ou clique em *Run workflow*).
3. Ao terminar, baixe o artefato **TradutorZH-PT-apk** e instale o `.apk` no A55
   (permita "instalar apps desconhecidos" para o navegador/Arquivos).

O workflow baixa o modelo de voz e o coloca no APK automaticamente.

## Primeiro uso
1. Abra o app e permita o microfone.
2. **Uma única vez, conectado ao Wi-Fi**, toque em *Baixar pacote de tradução* (~60 MB).
   O download é travado em *somente Wi-Fi* — o app nunca usa dados móveis.
3. Depois disso, pode ativar o modo avião: tudo funciona offline.

## Ajustes do microfone (botão "Mic")
- **Reconhecimento de voz (recomendado)** – fonte de áudio que a Samsung entrega sem compressão/AGC pesado, ideal para reconhecimento.
- **Câmera – mic traseiro** – usa o microfone de trás/superior. Melhor para **apontar o celular para quem está falando** (palestra, reunião, pessoa à sua frente).
- **Bruto** – sinal sem nenhum processamento (se o aparelho suportar).
- **Comunicação** – liga o cancelamento de ruído e eco do sistema; bom em ambiente barulhento, mas pode “comer” sílabas.
- **Microfone / entrada** – escolha o mic interno (inferior/traseiro), fone com fio ou microfone **USB-C** (lapela USB-C dá o melhor resultado com orador distante).
- **Ganho digital** – 1x a 4x, para quem fala longe. O medidor verde mostra o nível: o ideal é ficar no meio.
- **Redutor de ruído** – supressor de ruído do Android.
- **Falar tradução** – lê o português em voz alta. Use **com fones**; o app pausa a escuta enquanto fala.

Internamente: 16 kHz mono, blocos de 100 ms, thread de áudio prioritária, filtro passa-altas de ~80 Hz
(tira ronco de ar-condicionado, vento e batidas na mesa), AGC do sistema desligado e tela mantida acesa enquanto ouve.

## Limitações honestas
- O modelo pequeno do Vosk é bom para fala clara em mandarim padrão (Putonghua); sotaques fortes,
  cantonês ou muito ruído reduzem a precisão.
- A tradução do ML Kit é literal e passa pelo inglês internamente; serve para acompanhar o sentido, não para texto final.
