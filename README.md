# flowig
ImageJ plugin for motion estimation

## JavaCV
flowig uses JavaCV, read the README in https://github.com/bytedeco/javacv to learn how to get and use JavaCV.

## Preparations
Add ```javacpp.jar```, ```javacv.jar```, ```opencv.jav``` and ```opencv-PLATFORM.jar``` (e.g. ```opencv-linux-x86_64.jar```) to ImageJ's classpath. Some ImageJ distributions include a ```run``` file. To add java libraries to ImageJ's class path, simply add them to the java call in the ```run``` file using the ```-cp``` flag, like so:
```sh
java -Xmx512m\
    -cp /path/to/javacpp.jar \
    -cp /path/to/javacv.jar \
    -cp /path/to/opencv.jar \
    -cp /path/to/opencv-linux-x86_64.jar \
    -jar ij.jar
```

