#!/bin/sh

#/*******************************************************************************
# * Copyright (c) 2022 Contributors to the Eclipse Foundation.
# * 
# * All rights reserved. This program and the accompanying materials
# * are made available under the terms of the Eclipse Public License v2.0
# * and Eclipse Distribution License v1.0 which accompany this distribution.
# * 
# * The Eclipse Public License is available at
# *    http://www.eclipse.org/legal/epl-v20.html
# * and the Eclipse Distribution License is available at
# *    http://www.eclipse.org/org/documents/edl-v10.html.
# ******************************************************************************/
#
# requirements:
# 
# - activate account at https://portal.exoscale.com/register (please obey the resulting costs!)
# - install https://community.exoscale.com/documentation/tools/exoscale-command-line-interface/
#   and configure it
# - upload your ssh-key at https://portal.exoscale.com/compute/keypairs 
#   using the name "cali" or copy a different used name to "ssh_key_id" below.
#
# Adapt the the "vmsize" according your requirements and wanted price.
# See https://www.exoscale.com/pricing/ and
# run `exo compute instance create --help` to see the options.

: "${name:=s3benchmark}"
: "${coud_init:=cloud-config.yaml}"

ssh_key_id="cali"
vmsize="standard.medium"

get_ip() {
   ip=$(exo compute instance show ${name} -O text --output-template '{{ .IPAddress }}')
   echo "vm-ip: ${ip}"
}

wait_vm_ready() {
   status=$(exo compute instance show ${name} -O text --output-template '{{ .State }}')
   while [ "${status}" != "running" ] ; do
      echo "vm: ${status}, waiting for running"
      sleep 10
      status=$(exo compute instance show ${name} -O text --output-template '{{ .State }}')
   done
   echo "vm: ${status}"
}

create_cloud_vm() {
   echo "create exoscale server ${name}"

   exo compute security-group create ${name}-group

   exo compute security-group rule add ${name}-group \
    --description "ssh ipv4" \
    --protocol tcp \
    --network "0.0.0.0/0" \
    --port 22

   exo compute security-group rule add ${name}-group \
    --description "ssh ipv6" \
    --protocol tcp \
    --network "::/0" \
    --port 22
  
   exo compute instance create ${name} \
    --zone de-fra-1 \
    --disk-size 10 \
    --instance-type "${vmsize}" \
    --ipv6 \
    --ssh-key "${ssh_key_id}" \
    --cloud-init ${coud_init} \
    --security-group ${name}-group

   echo "wait to give vm time to finish the installation!"

   wait_vm_ready
	
   get_ip

   wait_cloud_init_ready

   echo "use: ssh root@${ip} to login!"
}

delete_cloud_vm() {
   echo "delete exoscale server ${name}"

   get_ip

   exo compute instance delete ${name}
   exo compute security-group delete ${name}-group --force

   echo "Please verify the successful deletion via the Web UI."

   echo "Remove the ssh trust for ${ip} with:"
   echo "ssh-keygen -f ~/.ssh/known_hosts -R \"${ip}\""
}

