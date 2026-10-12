@echo off
set CHIRP_POOL_SIZE=48
set CHIRP_MIN_IDLE=48
set CHIRP_CONNECTION_TIMEOUT=2000
"<home>\.gradle\jdks\amazon_com_inc_-25-amd64-windows.2\bin\java.exe" -Dgrails.env=loadtest -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=12 -jar "<home>\AppData\Local\Temp\opencode\ladder3\static-gorm.war" --spring.profiles.active=loadtest --logging.level.org.hibernate.SQL=DEBUG > "<home>\AppData\Local\Temp\opencode\ladder3\G0-sql.log" 2>&1
