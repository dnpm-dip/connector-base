# Changelog

## [1.1.1](https://github.com/dnpm-dip/connector-base/compare/v1.1.0...v1.1.1) (2025-11-19)


### Bug Fixes

* **deps:** bump service-base to v1.2.0 ([a565fe3](https://github.com/dnpm-dip/connector-base/commit/a565fe383f65ac2a326cb343e851796da482098f))

## [1.1.0](https://github.com/dnpm-dip/connector-base/compare/v1.0.0...v1.1.0) (2025-10-10)


### Features

* Upgraded dependency version: service-base ([c5a92fd](https://github.com/dnpm-dip/connector-base/commit/c5a92fd0dd0c8216b7fd3ea5d8f3b0374dd5cbd9))

## 1.0.0 (2025-08-06)


### Features

* Upgraded to Play 3.0 ([7c50485](https://github.com/dnpm-dip/connector-base/commit/7c504852e12ff434dc77c6688b0e0ed8d94a11f0))


### Bug Fixes

* Adapted scalac linting and fixed many reported errors (mostly unused imports) ([d3cbb95](https://github.com/dnpm-dip/connector-base/commit/d3cbb952bdd3eee3a88db4d00e37c6f83e495b4c))
* Added fallback strategy in case of missing virtual host config ([7488388](https://github.com/dnpm-dip/connector-base/commit/74883884710d3c26a3d5c5db2a6297795a9777d8))
* BrokerConnector: Added retry strategy for 'peer discovery' ([de75333](https://github.com/dnpm-dip/connector-base/commit/de75333e64bb4232a79ef0b8b84e4e6848716295))
* Corrected minor inconcistency ([08352c6](https://github.com/dnpm-dip/connector-base/commit/08352c6718922d501f25a26c9f27c7b86404fe98))
* Fixed bug potentially leading to badly concatenated URIs ([dc2e20d](https://github.com/dnpm-dip/connector-base/commit/dc2e20dc46975641e86b638238103d26afdd697a))
* Minor changes for more explicit origin of values ([8eb21a2](https://github.com/dnpm-dip/connector-base/commit/8eb21a2549631a85810ab999fceac52dc2323d65))
* Minor code clean-up ([785f6ad](https://github.com/dnpm-dip/connector-base/commit/785f6adb6200709284f8a16a93abe8b02212308b))
* Minor method parameter renaming ([3bff272](https://github.com/dnpm-dip/connector-base/commit/3bff272949131dbc7be20952527a0cf76ae19d0f))
* Refactored response handling for more differentiated error logging ([cae6cc2](https://github.com/dnpm-dip/connector-base/commit/cae6cc2a488539773d5b3712ecf89a8779f97f24))
* Refactored to use Retry utility ([6d662e8](https://github.com/dnpm-dip/connector-base/commit/6d662e8892342d5ef610c98a79c584919a9e2685))
* Refactoring: Simplified HttpConnector and changed BrokerConnector to use singleton WSClient etc ([1754d66](https://github.com/dnpm-dip/connector-base/commit/1754d66f3a2150aa57649088daf6d1e9755d53ee))
* Upgraded to Scala 2.13.16 ([ce13019](https://github.com/dnpm-dip/connector-base/commit/ce130197d20ba8dc0b6f208d5b044e557af24db3))
