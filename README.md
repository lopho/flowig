# flowig
ImageJ plugin for motion estimation

## Installation
### JavaCV
flowig uses JavaCV, read the README in https://github.com/bytedeco/javacv to learn how to get and use JavaCV.

Add ```javacpp.jar```, ```javacv.jar```, ```opencv.jav``` and ```opencv-PLATFORM.jar``` (e.g. ```opencv-linux-x86_64.jar```) to ImageJ's classpath. Some ImageJ distributions include a ```run``` file. To add java libraries to ImageJ's class path, simply add them to the java call in the ```run``` file using the ```-cp``` flag, like so:
```sh
java -Xmx512m\
    -cp /path/to/javacpp.jar \
    -cp /path/to/javacv.jar \
    -cp /path/to/opencv.jar \
    -cp /path/to/opencv-linux-x86_64.jar \
    -jar ij.jar
```
### ImageJ plugin
To install the plugin simply copy it into ImageJ's plugin directory in a subfolder named ```flowig```.
Before the first run you must compile it by via ```Plugins -> Compile and Run...```

## Development
### Netbeans
flowig can be run as a standalone application (mostly for development/debugging purposes). 

To set this up, simple instructions for netbeans are provided.

- Create a new java project from existing sources
- Select ```src``` as source directory
- Add ```ij.jar``` (either from an ImageJ distribution, or you can build it yourself), ```javacpp.jar```, ```javacv.jar```, ```opencv.jav``` and ```opencv-PLATFORM.jar``` (e.g. ```opencv-linux-x86_64.jar```) to the project's libraries
- When running the project, select ```flowig.Flowig``` as main class
