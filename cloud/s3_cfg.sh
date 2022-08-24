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
# Note: please only use temporary S3 access keys here in this config-file!
#
# Applied: 
#  sh> source s3_cfg.sh

# export S3_ENDPOINT=https://fra1.digitaloceanspaces.com
export S3_ENDPOINT=https://sos-de-fra-1.exo.io
export S3_BUCKET=devices
export S3_ACCESS_KEY=?????
export S3_ACCESS_SECRET=?????

