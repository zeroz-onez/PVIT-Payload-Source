# PVIT-Payload-Source

PVIT = Palos Verdes Institute of Technology

The Palos Verdes Institute of Technology (PVIT) is the award-winning engineering program of Palos Verdes High School. PVIT is comprised of multiple teams each hosting a unique set of STEM challenges. More about PVIT can be found at https://www.pvit.org/

The PVIT Space Team, which is currently working on launching a cubesat into space and is responsible for this project, maintains it's own website which can be found at https://www.seakingspace.com/

The PVIT_Payload_Sample code is intended to be run on the payload component of the SeaKing1 1U CubeSat and will communicate with the mainboard via a UART compliant USB cable. The payload device is an over the counter cell phone running Android OS. 

Serial connections from the payload device to the mainboard currently makes use of https://github.com/mik3y/usb-serial-for-android as a library resource. However, much of the code from this project is intended for a larger scope and thus superfluos for SeaKing1's needs. Consequently, the code will be optimized (reduced to only what is needed) for speed.

questions? Contact us at info@seakingspace.com
