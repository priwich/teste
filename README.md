# CamScan — Detector de câmeras / DVR / NVR na rede local (projeto Android)

Este é um projeto completo do Android Studio (Kotlin + Gradle). Eu não consigo compilar
o `.apk` dentro deste chat (o ambiente aqui não tem Android SDK nem acesso à internet para
baixar as ferramentas de build), mas este projeto está pronto para você compilar no seu
computador em poucos minutos.

## Como gerar o APK

1. Instale o **Android Studio** (gratuito): https://developer.android.com/studio
2. Abra esta pasta (`CamScanApp`) como projeto ("Open" no Android Studio).
3. Deixe o Gradle sincronizar sozinho (ele baixa as dependências automaticamente —
   isso sim precisa de internet, mas no seu computador).
4. Menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. O arquivo `.apk` aparece em `app/build/outputs/apk/debug/app-debug.apk`.
6. Para instalar no celular: ative "Fontes desconhecidas" (ou "Instalar apps desconhecidos")
   nas configurações do Android, transfira o `.apk` e toque nele — ou conecte o celular por
   USB com Depuração USB ativada e use **Run ▶** direto do Android Studio.

## O que o app faz

- Descobre seu IP e a faixa da sub-rede Wi-Fi atual.
- Varre as mesmas portas típicas de câmera/DVR/NVR da versão para Windows
  (80, 81, 443, 554, 8000, 8080, 8899, 9000, 34567, 37777, etc.).
- Testa RTSP (`OPTIONS`), banners HTTP (procura "camera", "dvr", "nvr", "hikvision",
  "dahua", servidores web embarcados como `uc-httpd`/`dnvrs-webs`) e ONVIF
  (`GetDeviceInformation` + WS-Discovery multicast).
- Classifica cada aparelho como ALTO / MEDIO / BAIXO, igual ao script do Windows.
- Reconhece sinais específicos de **gravador DVR/NVR**: porta 8000 (Hikvision),
  37777 (Dahua/Intelbras), 34567+8899 (XMEye/genérico) — e mostra isso numa seção à parte.
- Varre as redes Wi-Fi próximas atrás de hotspots típicos de câmera
  (ex.: `IPC-1234`, `GW_AP_xxxx`, `ESP32-CAM`) e de transmissores de fabricantes conhecidos.

## Diferenças em relação à versão para Windows (limitações do Android)

- **Sem tabela ARP/MAC dos outros aparelhos**: a partir do Android 6, apps não conseguem
  mais ler o endereço MAC de outros dispositivos na rede por questão de privacidade do
  sistema. Por isso a identificação aqui depende de portas abertas, respostas ONVIF e
  banners de páginas web — não do fabricante pelo MAC, como no script do Windows.
- **Varredura de Wi-Fi limitada**: o próprio Android restringe quantas vezes um app pode
  pedir uma nova varredura de Wi-Fi (poucas a cada 2 minutos) e exige permissão de
  localização concedida pelo usuário para enxergar o nome (SSID) das redes.
- **Tempo de varredura**: o app limita a varredura a uma faixa /24 (até 254 endereços)
  para terminar em tempo razoável no celular.

## Publicando no GitHub

Este projeto já vem com um `.gitignore` (ignora `build/`, `.gradle/`, `.idea/`,
`local.properties`, APKs e keystores — nenhum desses deve ir pro repositório) e um
workflow de **GitHub Actions** em `.github/workflows/build-apk.yml` que compila um APK de
debug automaticamente a cada `push` e deixa disponível para download na aba **Actions** do
repositório (em "Artifacts").

Passos para subir:

```bash
cd CamScanApp
git init
git add .
git commit -m "Scanner de cameras/DVR/NVR na rede local"
git branch -M main
git remote add origin https://github.com/SEU_USUARIO/SEU_REPOSITORIO.git
git push -u origin main
```

Sobre o `gradlew`: este projeto não inclui o `gradlew`/`gradlew.bat` nem o
`gradle-wrapper.jar` (são arquivos gerados, inclusive um binário). Ao abrir o projeto no
Android Studio pela primeira vez, ele mesmo oferece para gerar o wrapper — aceite. Se
preferir gerar manualmente e já tiver o Gradle instalado, rode `gradle wrapper
--gradle-version 8.4` dentro da pasta do projeto antes de dar commit; assim o workflow do
GitHub Actions (que usa `gradle` direto, sem depender do wrapper) continua funcionando de
qualquer jeito.

Se quiser, dá pra também assinar o APK de release no próprio Actions usando "GitHub
Secrets" para guardar a senha do keystore, ao invés de deixar no `local.properties` — nesse
caso é só avisar que eu ajusto o workflow.

## Aviso

Use apenas em redes que você tem autorização para analisar (sua casa, seu comércio, com
autorização do proprietário do imóvel, etc.). Deixe esse aviso visível na página do
repositório (README) já que é uma ferramenta de varredura de rede.
