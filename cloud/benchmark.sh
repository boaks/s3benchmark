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

: "${S3_ACCESS_KEY:=???}"
: "${S3_ACCESS_SECRET:=?!?!?}"
: "${S3_BUCKET:=cloudcoap}"
: "${S3_ACL:=public-read}"

ARGS=

if [ -n "${S3_ENDPOINT}" ]  ; then
	ARGS="${ARGS} --s3-endpoint ${S3_ENDPOINT}"
fi

if [ -n "${S3_REGION}" ]  ; then
	ARGS="${ARGS} --s3-region ${S3_REGION}"
fi

if [ -n "${S3_ACL}" ]  ; then
	ARGS="${ARGS} --s3-acl ${S3_ACL}"
fi

echo $ARGS

java -jar s3benchmark.jar --s3-access-key ${S3_ACCESS_KEY} --s3-secret ${S3_ACCESS_SECRET} --s3-bucket ${S3_BUCKET} ${ARGS} $@

