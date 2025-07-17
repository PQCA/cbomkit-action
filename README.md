# CBOMkit-action

GitHub Action to generate CBOMs. CBOMkit-action identifies all project modules contained in a github repository, scans the corresponding source code and produces a CBOM object per module. A project module is a part of a repo that can be used independently of other modules and may be published as a separate package. CBOMkit-action also generates a consolidated CBOM file that contains all crypto findings for the entire repository.

All CBOM objects can be  uploaded as json files in a github workflow artifact. This artifact is a zip file (`CBOM.zip`) that contains all CBOMs. For a particular package module the CBOM file is `cbom-<package_name>.json. The overall CBOM is named `cbom.json`. 

## Usage

Create a yaml file with a name of your choice in `.github/workflows`. The following example specifies the CBOM generation for a Java repository built with maven. 

```yaml
on:
  workflow_dispatch:

jobs:
  cbom-scan:
    runs-on: ubuntu-latest
    name: CBOM generation
    permissions:
      contents: write
      pull-requests: write
    steps:
      - name: Checkout repository
        uses: actions/checkout@v4
      - name: Build with Maven
        # When scanning Java code, the build should be completed beforehand
        run: mvn -B clean package -DskipTests
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
      - name: Create CBOM
        uses: PQCA/cbomkit-action@v2.1.0
        id: cbom
        env:
          CBOMKIT_LANGUAGES: java, python # or java or python
      - name: Commit changes to new branch
        # Allows persisting the CBOMs after job completion and
        # sharing them with another job in the same workflow.
        uses: actions/upload-artifact@v4
        with: 
          name: "CBOM"
          path: ${{ steps.cbom.outputs.pattern }}
          if-no-files-found: warn 
```

### Parameters

CBOMkit-action requires the following parameters passed as enviroment variables.  If run in a workflow, these variables are automatically set by the checkout step.

- GITHUB_WORKSPACE: Mandotory root directory of the cloned repository.
- GITHUB_OUTPUT: Mandatory filename containing the name pattern of the CBOM files used by uploader.
- GITHUB_SERVER_URL: (Optional) Github server url. Will be used to set the gitUrl property in the CBOM metadata which is used by CBOMkit viewer.
- GITHUB_REPOSITORY: (Optional) Github repository name. Will be used to set the
 gitUrl property in the CBOM metadata which is used by CBOMkit viewer. gitUrl metadata property = GITHUB_SERVER_URL + "/" + GITHUB_REPOSITORY.
- GITHUB_REF_NAME: (Optional) Github ref name. Will be used to set the revision (branch)  property in the CBOM metadata which is used by CBOMkit viewer.
- GITHUB_SHA: (Optional) Github commit SHA value. The first 7 characters will be used to set the commit property in the CBON metadata which is used by CBOMkit viewer.

### Scanning Options

CBOMkit-action's behavior can be controlled via the following additional environment variables.

- CBOMKIT_OUTPUT_DIR: (Optional) Output directory for CBOM files. Defaults to "cbom" if not set.
- CBOMKIT_LANGUAGES: (Optional) Comma-separated list of programming languages to scan. White-space will be ignored. Currently supported valeus are `java`, `python`, or `java, python`. Other values will be ignored.
- CBOMKIT_GENERATE_MODULE_CBOMS: (Optional) Generate CBOMs for project modules. Default value is `true`.
= CBOMKIT_WRITE_EMTPY_CBOMS: (Optional) Also write CBOMs with 0 findings. Default value is `true`.
- CBOMKIT_JAVA_REQUIRE_BUILD: (Optional) Java scans will terminate with an error if java files were found **and** the repo was not built prior to scanning. Default value is `true`. Setting it to `false` allows source-only scans of java repos with potentially lower accuracy.
- CBOMKIT_JAVA_JAR_DIR: (Optional) CBOMkit-action auto-constructs a list of jar/zip files to be considered for scanning. This option allows the specification of an additional directory for jar/zip files specific to the crypto library.

## Supported languages and libraries

The current scanning capabilities of the CBOMkit are defined by the [Sonar Cryptography Plugin's](https://github.com/IBM/sonar-cryptography) supported languages 
and cryptographic libraries:

| Language | Cryptographic Library                                                                         | Coverage | 
|----------|-----------------------------------------------------------------------------------------------|----------|
| Java     | [JCA](https://docs.oracle.com/javase/8/docs/technotes/guides/security/crypto/CryptoSpec.html) | 100%     |
|          | [BouncyCastle](https://github.com/bcgit/bc-java) (*light-weight API*)                         | 100%[^1] |
| Python   | [pyca/cryptography](https://cryptography.io/en/latest/)                                       | 100%     |

[^1]: We only cover the BouncyCastle *light-weight API* according to [this specification](https://javadoc.io/static/org.bouncycastle/bctls-jdk14/1.80/specifications.html)


While the CBOMkit's scanning capabilities are currently bound to the Sonar Cryptography Plugin, the modular 
design of this plugin allows for potential expansion to support additional languages and cryptographic libraries in 
future updates.
