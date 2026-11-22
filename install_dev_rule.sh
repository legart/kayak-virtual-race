#!/bin/bash
echo 'SUBSYSTEM=="hidraw", ATTRS{idVendor}=="16c0", ATTRS{idProduct}=="1503", MODE="0666", TAG+="uaccess"' | sudo tee /etc/udev/rules.d/99-dansprint.rules
sudo udevadm control --reload-rules
sudo udevadm trigger --subsystem-match=hidraw
