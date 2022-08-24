#!/bin/sh

#/*******************************************************************************
# * Copyright (c) 2022 Achim Kraus, cloudcoap.net.
# * 
# * All rights reserved. This program and the accompanying materials
# * are made available under the terms of the Eclipse Public License v2.0
# * and Eclipse Distribution License v1.0 which accompany this distribution.
# * 
# * The Eclipse Public License is available at
# *    http://www.eclipse.org/legal/epl-v20.html
# * and the Eclipse Distribution License is available at
# *    http://www.eclipse.org/org/documents/edl-v10.html.
# *
# ******************************************************************************/
#


FULLPATH=$(readlink -f $0)
INCPATH=${FULLPATH%/*}

export name=s3benchmark

user=user

wait_cloud_init_ready() {
   status=$(ssh -o "StrictHostKeyChecking=accept-new" root@${ip} "cloud-init status")

   while [ "${status}" != "status: done" ] ; do
      echo "cloud-init: ${status}, waiting for done"
      sleep 10
      status=$(ssh -o "StrictHostKeyChecking=accept-new" root@${ip} "cloud-init status")
   done
   echo "cloud-init: ${status}"
}

install_cloud_vm() {
   echo "install ${provider} client ${name}"

   get_ip

   ssh -o "StrictHostKeyChecking=accept-new" root@${ip} "exit"

   scp ../target/s3benchmark-0.0.1-SNAPSHOT.jar ${user}@${ip}:./s3benchmark.jar
   scp benchmark.sh ${user}@${ip}:.
   scp s3_*.sh ${user}@${ip}:.

   echo "use: ssh ${user}@${ip} to login!"
}

login_cloud_vm() {
   echo "login ${provider} client ${name}"

   get_ip

   wait_cloud_init_ready

   echo "use: ssh ${user}@${ip} to login!"
}

provider () {
  case $1 in
     "exo")
	provider="ExoScale"
	. $INCPATH/provider_exo.sh
	;;
     "aws")
	provider="AWS"
	. $INCPATH/provider_aws.sh
	;;
     "do")
	provider="DigitalOcean"
	. $INCPATH/provider_do.sh
	;;
     *)
	echo "Provider $1 not supported! Use exo, aws, or do."
	exit
	;;
  esac
}

jobs () {
  echo "${provider} $1"
  case $1 in
     "create")
	create_cloud_vm
	;;
     "delete")
	delete_cloud_vm
	;;
     "install")
        install_cloud_vm
	;;
     "login")
	login_cloud_vm
	;;
     *)
	echo "Job $1 unknown! Use create, install, delete, or login."
	exit
	;;
  esac
}

if [ -z "$1" ]  ; then
     echo "Missing cloud provider. Use: exo|aws|do"
     exit
else
	provider $1
	shift	
fi

if [ -z "$1" ]  ; then
     echo "Use: (create|install|login|delete)+"
     exit
else 
     JOBS=$@	
fi

for JOB in ${JOBS}; do
   jobs ${JOB}
done

