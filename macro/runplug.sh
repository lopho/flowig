#!/bin/sh
PLUGIN=`echo $1 | sed -e 's/_/ /g'`
echo "$PLUGIN#$2"
./ImageJ -macro runplug.ijm "$PLUGIN#$2"
