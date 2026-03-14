#!/usr/bin/env bash

set -e

# Artificially giving global flag after review-feedback. set 'global_flag=0' to disable interactive mode
global_flag=1

download_url=''
cli_path=''

if [[ $# -ge 1 && $1 == "-y" ]]; then
    global_flag=0
else
    global_flag=1
fi

function assert_flag() {
    if [[ $2 -eq 0 ]]; then
        return 0
    fi

    echo -n "$1 [Y/n] "
    read flag
    if [[ ! "${flag}" == "y" && ! "${flag}" == "Y" && ! "${flag}" == "" ]]; then
        echo -e "\033[32m$3 \033[0m"
        exit 1
    fi
}

function assert_download_path() {
    if [[ $2 -eq 0 ]]; then
        return 0
    fi

    echo -n "$1 [Y/n] "
    read flag
    if [[ "${flag}" == "n" ]] || [[ "${flag}" == "N" ]]; then
        read -ep "Specify an absolute path in '/xx/xx/xx/' format: " flag
        if [[ ! ${flag} == /* ]] || [[ ! ${flag} == */ ]]; then
            read -ep "Invalid directory. Specify an absolute path in '/xx/xx/xx/' format: " flag
            if [[ ! ${flag} == /* ]] || [[ ! ${flag} == */ ]]; then
                echo -e "\033[31mInstallation failed. Cause: Invalid directory. \033[0m"
                exit 1
            fi
        fi
        cli_path=${flag}
    elif [[ "${flag}" == "y" ]] || [[ "${flag}" == "Y" ]] || [[ "${flag}" == "" ]]; then
        return 0
    else
        echo -e "\033[31mInstallation failed. Cause: Invalid characters. \033[0m"
        exit 1
    fi
}

function check_system() {
    if [ "$(uname)" == "Darwin" ]; then
        if [ $(echo $HOSTTYPE) == 'x86_64' ]; then
            download_url="https://ap-southeast-3-hwcloudcli.obs.ap-southeast-3.myhuaweicloud.com/cli/latest/huaweicloud-cli-mac-amd64.tar.gz"
        else
            download_url="https://ap-southeast-3-hwcloudcli.obs.ap-southeast-3.myhuaweicloud.com/cli/latest/huaweicloud-cli-mac-arm64.tar.gz"
        fi
    elif [ "$(uname)" == "Linux" ]; then
        if [ $(echo $HOSTTYPE) == 'aarch64' ]; then
            download_url="https://ap-southeast-3-hwcloudcli.obs.ap-southeast-3.myhuaweicloud.com/cli/latest/huaweicloud-cli-linux-arm64.tar.gz"
        elif [ $(echo $HOSTTYPE) == 'x86_64' ]; then
            download_url="https://ap-southeast-3-hwcloudcli.obs.ap-southeast-3.myhuaweicloud.com/cli/latest/huaweicloud-cli-linux-amd64.tar.gz"
        fi
    elif [ "$(expr substr $(uname -s) 1 10)" == "MINGW64_NT" ]; then
        echo -e "\033[31mInstallation failed. Cause: The script is not supported in the current environment. \033[0m"
        exit 1
    else
        echo -e "\033[31mInstallation failed. Cause: KooCLI is not supported in this environment. \033[0m"
        exit 1
    fi
}

function check_integrity() {
    curl -L ${download_url}.sha256 -o ${cli_path}huaweicloud-cli.tar.gz.sha256
    calculated_hash="$(sha256sum ${cli_path}huaweicloud-cli.tar.gz)"
    download_hash="$(cat ${cli_path}huaweicloud-cli.tar.gz.sha256)"
    if [ "$(echo ${calculated_hash% *})" != "$(echo ${download_hash% *})" ]; then
        rm -rf ${cli_path}huaweicloud-cli.tar.gz
        echo -e "\033[31mInstallation failed. Cause: Failed to verify integrity for the downloaded package. \033[0m"
        exit 1
    fi
    rm -rf ${cli_path}huaweicloud-cli.tar.gz.sha256
}

setup() {
    check_system
    cli_path="/usr/local/hcloud/"
    assert_download_path "Download KooCLI to the default '/usr/local/hcloud/' directory? To change directory, enter 'n'." ${global_flag}
    if [ ! -d ${cli_path} ]; then
        mkdir -p ${cli_path}
    fi
    curl -L ${download_url} -o ${cli_path}huaweicloud-cli.tar.gz
    check_integrity
    tar -zxvf ${cli_path}huaweicloud-cli.tar.gz -C ${cli_path}
    rm -rf ${cli_path}huaweicloud-cli.tar.gz

    cli_link_path="/usr/local/bin/hcloud"
    if [ -e ${cli_link_path} ] || [ -L ${cli_link_path} ]; then
        assert_flag "Delete existing '/usr/local/bin/hcloud' and move KooCLI to directory '/usr/local/bin/'?" ${global_flag} "KooCLI installed. Run \`${cli_path}hcloud\` command in any directory."
        rm -rf ${cli_link_path}
    else
        assert_flag "Move KooCLI to directory '/usr/local/bin/'?" ${global_flag} "KooCLI installed. Run \`${cli_path}hcloud\` command in any directory."
        if [ ! -d /usr/local/bin/ ]; then
            mkdir -p /usr/local/bin/
        fi
    fi
    mv ${cli_path}hcloud /usr/local/bin/

    echo -e "\033[32mKooCLI installed. \033[0m"
    exit
}

# ensure the whole file is downloaded before executing
setup
