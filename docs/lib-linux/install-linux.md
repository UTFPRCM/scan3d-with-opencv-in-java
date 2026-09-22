# Instalacao historica no Linux

Este documento preserva as instrucoes usadas originalmente para executar o prototipo em Ubuntu 16.04 ou superior, Eclipse Luna e OpenCV 2.4. Elas sao reproduzidas para referencia historica; os enderecos, pacotes e versoes podem estar obsoletos e nao constituem uma instalacao moderna recomendada.

## Requisitos

- Ubuntu 16.04 ou superior;
- Eclipse Luna para Java;
- Java 8 ou superior;
- Apache Ant;
- OpenCV 2.4 com bindings Java;
- biblioteca nativa OpenCV compativel com a arquitetura do sistema.

O projeto usa um arquivo `.jar` no Build Path e uma biblioteca nativa (`.so`) configurada no sistema ou na localizacao de biblioteca nativa do Eclipse.

## Java e Apache Ant

O procedimento original usava o instalador Oracle disponibilizado por um PPA. Esse PPA e esse pacote podem nao existir mais. Em uma instalacao atual, use uma distribuicao suportada do JDK e confirme que `java`, `javac` e `JAVA_HOME` apontam para a mesma versao.

```bash
sudo add-apt-repository ppa:webupd8team/java
sudo apt-get update
sudo apt-get install oracle-java9-installer
```

Para escolher a versao padrao:

```bash
sudo update-alternatives --config java
sudo update-alternatives --config javac
```

Configure `JAVA_HOME` em `/etc/environment`, substituindo o caminho pelo JDK instalado:

```text
JAVA_HOME="/usr/lib/jvm/java-8-oracle"
```

Recarregue e confirme a configuracao:

```bash
source /etc/environment
echo "$JAVA_HOME"
```

Instale o Ant:

```bash
sudo apt-get install ant
```

## Eclipse Luna

O procedimento original baixava o Eclipse Luna para Linux 64-bit. O endereco abaixo pode estar indisponivel; use uma distribuicao arquivada ou uma IDE atual se precisar reproduzir o ambiente.

```bash
wget -c http://eclipse.c3sl.ufpr.br/technology/epp/downloads/release/luna/SR2/eclipse-java-luna-SR2-linux-gtk-x86_64.tar.gz -O eclipse.tar.gz
sudo tar -zxvf eclipse.tar.gz -C /opt/
sudo mv /opt/eclipse*/ /opt/eclipse
```

Para criar um lancador no menu do sistema, o procedimento original usava:

```bash
sudo wget https://dl2.macupdate.com/images/icons128/11662.png -O /opt/eclipse/eclipse.png
echo -e '[Desktop Entry]\n Version=1.0\n Name=eclipse\n Exec=/opt/eclipse/eclipse\n Icon=/opt/eclipse/eclipse.png\n Type=Application\n Categories=Application' | sudo tee /usr/share/applications/eclipse.desktop
sudo chmod +x /usr/share/applications/eclipse.desktop
cp /usr/share/applications/eclipse.desktop ~/Desktop
```

## Compilar OpenCV 2.4 com Java

O projeto original recomendava compilar o OpenCV estatico para que a biblioteca Java dependesse menos de outras bibliotecas OpenCV:

```bash
git clone https://github.com/Itseez/opencv.git
cd opencv
git checkout -b 2.4 origin/2.4
mkdir build
cd build
cmake -D BUILD_SHARED_LIBS=OFF ..
```

Antes de compilar, confirme na saida do CMake que os bindings Java foram encontrados. O resultado esperado na epoca era semelhante a:

```text
Java:
  ant:                         /usr/bin/ant
  JNI:                         /usr/lib/jvm/java-8-oracle/include ...
  Java tests:                  YES
```

Se `ant` aparecer como `NO`, instale-o. Se `JNI` nao for encontrado, corrija `JAVA_HOME` e execute o CMake novamente.

Compile usando a quantidade de jobs adequada ao computador:

```bash
make -j8
```

Ao final, a compilacao deve produzir um JAR Java em `build/bin/` e a biblioteca nativa em `build/lib/`. Os nomes exatos dependem da versao compilada.

## Configurar no Eclipse

1. Abra `Window -> Preferences -> Java -> Build Path -> User Libraries`.
2. Clique em `New` e crie uma biblioteca chamada, por exemplo, `OpenCV-2.4`.
3. Selecione `Add External JARs...` e escolha o JAR produzido em `opencv/build/bin/`.
4. Configure `Native library location` para `opencv/build/lib/`.
5. No projeto Java, abra `Properties -> Java Build Path -> Libraries`.
6. Use `Add Libraries -> User Library`, selecione `OpenCV-2.4` e confirme.

O JAR historico distribuido neste repositorio esta em `projects/OpenCv-Java-Example01/lib/opencv-2413.jar` e `projects/OpenCv-Java-Example02/lib/opencv-2413.jar`. O `.so` precisa ser obtido para o sistema Linux em que o programa sera executado.

## Executar o prototipo

Os dois exemplos possuem um caminho de entrada hardcoded em `MainActivity.test0()`. Altere-o para uma pasta que contenha as imagens JPG/JPEG e uma copia de `TextMatrix.txt`; depois execute `mainOpenCv.MainActivity` pelo Eclipse.

O programa cria `out/` dentro da pasta de entrada e imprime no console a quantidade de quadrados, triangulos e imagens processadas.

## Observacoes

- Este tutorial nao configura um projeto Android.
- A biblioteca OpenCV 2.4 e a API `Highgui` sao antigas.
- Um `.so` compilado para Linux nao funciona diretamente no macOS ou Android.
- Para uma migracao atual, use uma versao suportada do OpenCV Android e substitua o carregamento nativo e as APIs antigas.