<div align="center">

  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Logo do HS Voice Translator" width="120" />

  # 🎙️ HS Voice Translator

  **Tradução e dublagem de falas de jogos em tempo real, com voz e legendas em português brasileiro.**

  Aplicativo Android que captura o áudio interno compatível do dispositivo e usa o Gemini Live Translate para traduzir as falas.

</div>

---

## Recursos

- Tradução de fala em tempo real para o idioma escolhido.
- Voz traduzida, legendas ou voz com legendas.
- Exibição opcional de legendas originais e traduzidas.
- Ajuste separado do volume da voz traduzida e do áudio original.
- Escolha entre diferentes vozes de síntese.
- Controle flutuante para acessar os comandos durante o uso de outros aplicativos.
- Interface em português brasileiro.

## Requisitos

- Android 10 ou superior.
- Conexão com a internet.
- Chave de API do Gemini obtida no [Google AI Studio](https://aistudio.google.com/app/apikey).

## Instalação e configuração

1. Baixe o arquivo APK na seção **Artifacts** da execução mais recente do [Android CI](https://github.com/tryonBrasil/HS-DUBLE/actions).
2. Instale o APK no Android. Se necessário, autorize a instalação de aplicativos dessa origem nas configurações do sistema.
3. Abra o HS Voice Translator e entre em **Configurações**.
4. Informe sua chave de API do Gemini.
5. Na tela principal, escolha o idioma de destino e o modo de dublagem.
6. Toque em **Iniciar dublagem** e conceda as permissões solicitadas pelo Android, incluindo a captura de áudio da tela.

A captura de áudio interno depende das permissões do Android e do suporte do aplicativo que está reproduzindo o som. Alguns aplicativos protegidos podem impedir a captura.

## Compilar o projeto

É necessário ter o JDK 17 e o Android SDK configurados.

No Linux ou macOS:

```bash
git clone https://github.com/tryonBrasil/HS-DUBLE.git
cd HS-DUBLE
git checkout hs-voice-translator-setup
./gradlew assembleDebug assembleRelease
```

Os APKs são gerados em `app/build/outputs/apk/`.

A assinatura de lançamento só será aplicada quando o arquivo de assinatura e as variáveis `HS_STORE_PASSWORD`, `HS_KEY_ALIAS` e `HS_KEY_PASSWORD` estiverem configurados. Não coloque senhas ou chaves privadas no repositório.

## Privacidade e segurança

O aplicativo precisa acessar o áudio interno autorizado pelo usuário para fazer a tradução. O áudio é enviado ao serviço Gemini para processamento. Não compartilhe sua chave de API: ela é uma credencial privada. Antes de distribuir publicamente, a configuração de produção deve proteger essa credencial, preferencialmente com um serviço intermediário ou tokens temporários.

## Estado do projeto

Esta versão está em desenvolvimento e precisa ser testada em dispositivos reais. Uma compilação bem-sucedida não garante, por si só, que a captura de áudio e a dublagem funcionarão em todos os jogos ou celulares.

## Licença

Consulte o arquivo [LICENSE](LICENSE) para conhecer os termos da licença.
