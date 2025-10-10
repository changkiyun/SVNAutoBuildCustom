// Groovy
/**
 * 공유 라이브러리 – 빌드·배포 파이프라인
 *
 * 호출 예:
 *   buildAndDeploy(
 *       CREDENTIALSID      : 'svn-cred-id',
 *       REMOTE_PATH        : 'svn://example.com/repo/',
 *       BRANCH_NAME        : 'branches/myBranch/',
 *       SPECIFIC_REVISION  : '1234',
 *       ZENIUS_VERSION     : '1.2.3',
 *       DEPLOY_FOLDER      : 'deploy_pkg',
 *       SCP_USER           : 'deployUser',
 *       SCP_PW             : 'deployPw',
 *       TEST_SERVER_IP     : '192.0.2.10',
 *       TEST_WEB_PATH      : '/home/deploy/web',
 *       FILEList      : ["", ""]
 *       INIT           : true,
 *   )
 */
def call(Map cfg = [:]) {
    pipeline {
        agent any
        stages {
            stage('clear') {
                steps {
                    script {
                        //초기화시 워크스페이스 데이터를 삭제
                        if (cfg.INIT != null && cfg.INIT == true) {
                            sh "rm -rf *"
                        }
                    }
                }
            }
            stage('Checkout Revision') {
                steps {
                    script {
                        sh """
                            DIR_PATH="${env.WORKSPACE}/specific"
                            if [ ! -d "\$DIR_PATH" ]; then
                                mkdir -p "\$DIR_PATH"
                            fi
                        """
                    }
                    
                    // 특정 리비전으로 SVN 체크아웃
                    checkout([$class: 'SubversionSCM', 
                            locations: [[credentialsId: "${cfg.CREDENTIALSID}",
                                        depthOption: 'infinity',
                                        ignoreExternalsOption: true,
                                        local: "specific", 
                                        remote: "${cfg.REMOTE_PATH}"+"${cfg.BRANCH_NAME}"+'@' + "${cfg.SPECIFIC_REVISION}"]],
                            workspaceUpdater: [$class: 'UpdateUpdater']])
                }
            }
            stage('Build Specific Revision with Ant') {
                steps {
                    script {
                        sh "cp -f /var/jenkins_home/buildFile/build.xml ${env.WORKSPACE}/specific/build.xml" // 파일 복사
                        sh "cp -f /var/jenkins_home/buildFile/build_${cfg.ZENIUS_VERSION}.properties ${env.WORKSPACE}/specific/build.properties" // 파일 복사
                    }
                    
                    
                    // Ant를 사용하여 특정 리비전에서 빌드
                    withAnt(installation: 'ant') {
                        sh "ant -f ${env.WORKSPACE}/specific/build.xml clean build"
                    }
                }
            }
            stage('File Extraction') {
                steps {
                    script {
                        // null 체크 + 비어 있지 않은지 확인
                        if (cfg.FILEList && cfg.FILEList.size() > 0) {
                            println "cfg.FILEList에는 하나 이상의 원소가 있습니다."

                            def FILETXT = cfg.FILEList.collect{ it }.join("\n")
                            def extractFolderName = (cfg.ZENIUS_VERSION == "zenius8") ? "extract8" : "extract7"

                            sh "rm -rf ${env.WORKSPACE}/${extractFolderName}"
                            sh "cp -r /var/jenkins_home/extract/${extractFolderName} ${env.WORKSPACE}/${extractFolderName}"
                            sh """echo "${FILETXT}" > ${env.WORKSPACE}/${extractFolderName}/FILELIST.txt"""
                            println "파일리스트 생성"    
                            sh "mv ${env.WORKSPACE}/specific/build/${cfg.ZENIUS_VERSION}.zip ${env.WORKSPACE}/${extractFolderName}/01_ORIGIN/${cfg.ZENIUS_VERSION}.zip"
                            sh "unzip -o ${env.WORKSPACE}/${extractFolderName}/01_ORIGIN/${cfg.ZENIUS_VERSION}.zip -d ${env.WORKSPACE}/${extractFolderName}/01_ORIGIN/${cfg.ZENIUS_VERSION}/"
                            sh "mv ${env.WORKSPACE}/specific/build/${cfg.ZENIUS_VERSION}_oz.zip ${env.WORKSPACE}/${extractFolderName}/01_ORIGIN/${cfg.ZENIUS_VERSION}_oz.zip"
                            sh "unzip -o ${env.WORKSPACE}/${extractFolderName}/01_ORIGIN/${cfg.ZENIUS_VERSION}_oz.zip -d ${env.WORKSPACE}/${extractFolderName}/01_ORIGIN/${cfg.ZENIUS_VERSION}_oz/"
                            
                            println "압축해제" 
                            sh "java -jar ${env.WORKSPACE}/${extractFolderName}/03_FileExtractor/MakeFile.jar ${env.WORKSPACE}/${extractFolderName} ${cfg.PACKAGE_NAME}"

                            println "추출완료"
                            sh """
                                cd ${env.WORKSPACE}/${extractFolderName}/02_COPY
                                zip -r ${cfg.DEPLOY_FOLDER}.zip ./*
                                mv ${cfg.DEPLOY_FOLDER}.zip ${env.WORKSPACE}/${cfg.DEPLOY_FOLDER}.zip
                            """
                            
                        }
                    }
                }
            }
            stage('Execute Remote Command') {
                steps {
                    script {
                        def transferArgs = [
                            sourceFiles: "${cfg.DEPLOY_FOLDER}.zip",
                            remoteDirectory: "${cfg.TEST_WEB_PATH}"
                        ]

                        if (cfg.AUTO_RELOAD != null && cfg.AUTO_RELOAD) {
                            transferArgs.execCommand = """
                                # 압축 풀기
                                unzip -o ${cfg.TEST_WEB_PATH}/${cfg.DEPLOY_FOLDER}.zip -d ${cfg.TEST_WEB_PATH}/
                                # Tomcat 재시작
                                ${cfg.TEST_WEB_PATH}/../bin/shutdown.sh
                                ${cfg.TEST_WEB_PATH}/../bin/startup.sh
                            """
                        }
                        sshPublisher(
                            continueOnError: false,
                            failOnError: true,
                            publishers: [
                                sshPublisherDesc(
                                    configName: "${cfg.TEST_SERVER_IP}",
                                    transfers: [
                                        sshTransfer(transferArgs)
                                    ]
                                )
                            ]
                        )
                    }
                }
            }
            stage('Archive Differences') {
                steps {
                    // 생성된 ${cfg.DEPLOY_FOLDER}.zip 파일을 아티팩트로 아카이브
                    archiveArtifacts artifacts: "${cfg.DEPLOY_FOLDER}.zip"
                }
            }
        }
    }
}