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
# requirements:
#
# - activate account at https://www.digitalocean.com/ (please obey the resulting costs!)
# - install https://docs.digitalocean.com/reference/doctl/how-to/install/
# - upload your ssh-key to https://cloud.digitalocean.com/account/security and copy 
#   the fingerprint of the ssh-key to "ssh_key_id" below.
#
# Adapt the the "vmsize" according your requirements and wanted price.
# See https://www.digitalocean.com/pricing/ and
# run `doctl compute size list` to see the options.
#
# Available regions:
# run `doctl compute run `doctl compute size list` list`

: "${name:=s3benchmark}"
: "${coud_init:=cloud-config.yaml}"

# edit the ssh_key_id
ssh_key_id="7d:2a:03:72:eb:d6:95:52:1d:7f:77:73:22:35:0f:93"
#vmsize="s-2vcpu-2gb"
vmsize="c-2"

get_ip() {
   ip=$(doctl compute droplet get ${name} --template {{.PublicIPv4}})
   echo "vm-ip: ${ip}"
}

wait_vm_ready() {
   status=$(doctl compute droplet get ${name} --template {{.Status}})
   while [ "${status}" != "active" ] ; do
      echo "vm: ${status}, waiting for active"
      sleep 10
      status=$(doctl compute droplet get ${name} --template {{.Status}})
   done
   echo "vm: ${status}"
}

create_cloud_vm() {
   echo "create digitalocean client ${name}"
   
   doctl compute droplet create ${name} \
    --image "ubuntu-20-04-x64" \
    --enable-ipv6 \
    --region "fra1" \
    --size "${vmsize}" \
    --ssh-keys "${ssh_key_id}" \
    --user-data-file "${coud_init}" \
    --wait 	

   echo "wait to give vm time to finish the installation!"

   wait_vm_ready

   doctl compute droplet get ${name} --format ID,PublicIPv4,PublicIPv6

   get_ip

   wait_cloud_init_ready
	
   echo "use: ssh root@${ip} to login!"
}

delete_cloud_vm() {
   echo "delete digitalocean client ${name}"

   get_ip

   doctl compute droplet delete ${name}

   echo "Please verify the successful deletion via the Web UI."

   echo "Remove the ssh trust for ${ip} with:"
   echo "ssh-keygen -f ~/.ssh/known_hosts -R \"${ip}\""
}

