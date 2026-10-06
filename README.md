# Kino bridge fork

This is a fork of [recloudstream/cloudstream](https://github.com/recloudstream/cloudstream) kept by the Kino project
on branch `kino-bridge`. It is **not** the CloudStream app and is not affiliated with the CloudStream developers;
please do not report CloudStream issues here.

**What it is.** A headless Android application, the `:bridge` module (`com.kino.csbridge`), that runs CloudStream
extensions (`.cs3` plugins) for [Kino](https://github.com/kinotvapp/kino-light), an Android video app. It has no UI.

**Why it exists.** Kino lets people use CloudStream extensions without giving third-party plugin code the network or
Kino's own process: the bridge is a separate, sandboxed plugin host that holds **no network permission** (no
`INTERNET`, `ACCESS_NETWORK_STATE` or `ACCESS_WIFI_STATE`). Every request a plugin makes goes to Kino over IPC, and
Kino decides what reaches the network. The fork follows upstream closely: everything it adds lives in `bridge/`,
`bridge-contract/`, `scripts/`, `.github/` and `BRIDGE.md`, plus a few named upstream edits. Details in
[BRIDGE.md](BRIDGE.md).

**License and source.** GPL-3.0, like upstream (see [LICENSE](LICENSE)). The complete corresponding source of every
bridge APK Kino distributes is this repository at the tag matching the APK's `versionName`. Exception: `bridge-contract/`
(the AIDL interfaces and wire protocol shared with Kino) is Apache-2.0, full text in
[bridge-contract/LICENSE](bridge-contract/LICENSE).

Upstream's README follows, unchanged.

---

# CloudStream

**⚠️ Warning: By default, this app doesn't provide any video sources; you have to install extensions to add functionality to the app.**
<p align="left"><a href="https://github.com/recloudstream/cloudstream/releases/download/pre-release/app-prerelease-release.apk"><img src=".github/images/download-cloudstream-pre.png" alt="Download Cloudstream Pre-Release" width=430/></a></p>

[![Discord](https://invidget.switchblade.xyz/5Hus6fM)](https://discord.gg/5Hus6fM)


## Table of Contents: 
+ [About Us:](#about_us)
+ [Installation Steps:](#install_rules)
+ [Contributing:](#contributing)
+ [Issues:](#issues)
  + [Bugs Reports:](#bug_report)
  + [Enhancement:](#enhancment)
+ [Extension Development:](#extensions)
+ [Language Support:](#languages)
+ [Further Sources](#contact_and_sources)


<a id="about_us"></a>

## About us: 

**CloudStream is a media center that prioritizes and emphasizes complete freedom and flexibility for users and developers.** 

CloudStream is an extension-based multimedia player with tracking support. There are extensions to view videos from: 

+ [Librevox (audio-books)](https://librivox.org/) 
+ [Youtube](https://www.youtube.com/)
+ [Twitch](https://www.twitch.tv/)
+ [iptv-org (A collection of publicly available IPTV (Internet Protocol television) channels from all over the world.)](https://github.com/iptv-org/iptv) 
+ [nginx](https://nginx.org/)
+ And more... 


**Please don't create illegal extensions or use any that host any copyrighted media.** For more details about our stance on the DMCA and EUCD, you can read about it on our organization: [reCloudStream](https://github.com/recloudstream)

#### Important Copyright Note: 

Our documentation is unmaintained and open to contributions; therefore, apps and sources, extensions in recommended sources, and recommended apps are not officially moderated or endorsed by CloudStream; if you or another copyright owner identify an extension that breaches your copyright, please let us know. 


#### Features:
+ **AdFree**, No ads whatsoever
+ No tracking/analytics
+ Bookmarks
+ Phone and TV support
+ Chromecast
+ Extension system for personal customization


<a id="install_rules"></a>

## Installation: 

Our documentation provides the steps to install and configure CloudStream for your streaming needs.

[Getting Started With CloudStream:](https://recloudstream.github.io/csdocs/)

<a id="contributing"></a>

## Contributing:
We **happily** accept any contributions to our project. To find out where you can start contributing towards the project, please look [at our issues tab](/cloudstream/issues)



<a id="issues"></a> 
 
### Issues: 
While we **actively** accept issues and pull requests, we do require you fill out an [template](https://github.com/recloudstream/cloudstream/issues/new/choose) for issues. These include the following:

<a id="bug_report"></a>

- [Bug Report Template: ](https://github.com/recloudstream/cloudstream/issues/new?assignees=&labels=bug&projects=&template=application-bug.yml)
  - For bug reports, we want as much info as possible, including your downloaded version of CloudeStream, device and updated version (if possible, current API),
    expected behavior of the program, and the actual behavior that the program did, most importantly we require clear, reproducible steps of the bug. If your bug can't be       reproduced, it is unlikely we'll work on your issue.
    
<a id="enhancment"></a>
  
- [Feature Request Template: ](https://github.com/recloudstream/cloudstream/issues/new?assignees=&labels=enhancement&projects=&template=feature-request.yml)
  - Before adding a feature request, please check to see if a feature request already has been requested.  


### Extensions:
 
**Further details on creating extensions for CloudStream are found in our documentation.**

[Guide: For Extension Developers](https://recloudstream.github.io/csdocs/devs/gettingstarted/) 

<a id="contact_and_sources"></a>

## Further Sources: 

As well as providing clear install steps, our [website](https://dweb.link/ipns/cloudstream.on.fleek.co/) includes a wide variety of other tools, such as: 
- [Troubleshooting](https://recloudstream.github.io/csdocs/troubleshooting/)
- [Further CloudStream Repositories](https://recloudstream.github.io/csdocs/repositories/) 
- Set-Up for other devices, such as:
  - [Android TV](https://recloudstream.github.io/csdocs/other-devices/tv/)
  - [Windows](https://recloudstream.github.io/csdocs/other-devices/windows/)
  - [Linux](https://recloudstream.github.io/csdocs/other-devices/linux/)
- And more...

<a id="languages"> </a>  

### Supported languages:

Even if you can't contribute to the code or documentation, we always look for those who can contribute to translation and language support. Your contribution is exceptionally appreciated; you can check our translation from the figure below. 

<a href="https://hosted.weblate.org/engage/cloudstream/">
  <img src="https://hosted.weblate.org/widgets/cloudstream/-/app/multi-auto.svg" alt="Translation status" />
</a>
