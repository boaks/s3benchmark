# S3 requests benchmark

!!! Under construction !!!

Simple S3 requests benchmark.

## Build

### Requirements

Though the benchmark is implemented in java, you need a Java Development Kit to build it.
Using Ubuntu 20.04 LTS you may install it with:

```
sudo apt install openjdk-17-jdk-headless
```

To build the application also the java build system "maven" is required:

```
sudo apt install maven
```

### Build it

Use maven:

```
mvn clean install
```

That may take a while, especially at the first time. The resulting java application jar will be found in the folder "target".

## General Usage

Start the benchmark using the provided script [benchmark.sh](cloud/benchmark.sh):

```sh
java -jar s3benchmark-?.?.?-SNAPSHOT.jar -h

Usage: S3Benchmark [-h] [-k=<keys>] [-m=<method>] [-p=<payload>]
                   [-pl=<payloadLength>] [-r=<requests>]
                   --s3-access-key=<accessKey> [--s3-acl=<acl>]
                   [--s3-bucket=<bucket>] [--s3-concurrency=<concurrency>]
                   [--s3-endpoint=<endpoint>] [--s3-region=<region>]
                   --s3-secret=<secret>
  -h, --help                 display a help message
  -k, --keys=<keys>          Number of keys. Default 200
  -m, --method=<method>      Method to test. GET or PUT. Default PUT
  -p, --payload=<payload>    Payload. Applies format(payload, request-number).
      -pl, --payload-length=<payloadLength>
                             Payload length.
  -r, --requests=<requests>  Number of keys. Default 100000
      --s3-access-key=<accessKey>
                             s3 access key.
      --s3-acl=<acl>         s3 canned acl. e.g. public-read
      --s3-bucket=<bucket>   s3 bucket. Default: devices
      --s3-concurrency=<concurrency>
                             s3 concurrency. Default 200
      --s3-endpoint=<endpoint>
                             s3 endoint URI. e.g.: https://sos-de-fra-1.exo.io
                               for ExoScale in DE-FRA1.
      --s3-region=<region>   s3 region. Only AWS regions are supported.
                               Default: 'us-east-1'. (For other providers, try,
                               if the default works).
      --s3-secret=<secret>   s3 secret access key.
```

To see the set of options and arguments.

## Scripts

The project contains several shell script in the folder "cloud" to install the benchmark on cloud vms and to run the benchmark.

- [benchmark.sh](cloud/benchmark.sh) : start the benchmark. it provides the --s3 arguments from environment variables
- [s3_cfg.sh](cloud/s3_cfg.sh)       : setup environment variables

Please edit "s3_cfg.sh" and insert temporary S3 access keys. Copy the application .jar from the folder "target" into the "cloud" folder and rename it into "s3benchmark.jar".

Usage:

```sh
source s3_cfg.sh
./benchmark.sh
```

- [cloud_config.yaml](cloud/cloud_config.yaml) : cloud-init configuration.
- [deploy.sh](cloud/deploy.sh)         : deploy script
- [provider_exo.sh](cloud/provider_exo.sh)  : provider script for ExoScale
- [provider_aws.sh](cloud/provider_aws.sh)  : provider script for AWS
- [provider_do.sh](cloud/provider_do.sh)  : provider script for DigitalOcean

See deploy scripts for instruction. Provide user ssh key in `cloud_config.yaml`. Setup and prepare `provider-???.sh` according the documentation in that files.
