package com.example.file.service;

import com.example.common.cache.CacheService;
import com.example.common.config.cache.CommonCacheConfig;
import com.example.common.exception.BusinessException;
import com.example.common.service.FileService;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cloudflare R2文件服务实现类
 */
@Service
@DubboService
public class CloudflareR2FileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(CloudflareR2FileServiceImpl.class);

    // 缓存过期时间（分钟）
    private static final long CACHE_EXPIRATION_MINUTES = 30;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private S3Presigner s3Presigner;

    @Autowired
    private String bucketName;

    @Autowired
    private CacheService cacheService;

    @Value("${cloudflare.r2.allowed-types}")
    private String allowedTypes;

    @Override
    public String generateUploadPresignedUrl(Long userId, String fileType, String contentType, int expiration)
            throws BusinessException {
        try {
            // 检查contentType是否允许
            String extension = getExtensionFromContentType(contentType);
            if (!isValidFileType(extension)) {
                throw new BusinessException(400, "不支持的文件类型，允许的类型：" + allowedTypes);
            }

            // 生成唯一的文件名
            String fileName = generateFileName(userId, fileType, extension);

            // 创建预签名上传请求
            PutObjectRequest objectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileName)
                    .contentType(contentType)
                    .build();

            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(expiration))
                    .putObjectRequest(objectRequest)
                    .build();

            // 生成预签名URL
            String presignedUrl = s3Presigner.presignPutObject(presignRequest).url().toString();

            // 返回包含文件名和URL的响应
            return presignedUrl + ":::" + fileName; // 使用分隔符，方便后续处理
        } catch (S3Exception e) {
            throw new BusinessException(500, "生成预签名URL失败：" + e.getMessage());
        }
    }

    @Override
    public String generateDownloadPresignedUrl(String fileName, int expiration) throws BusinessException {
        // 尝试从缓存获取
        String cachedUrl = cacheService.get(CommonCacheConfig.FILE_URL_CACHE, fileName);
        if (cachedUrl != null) {
            return cachedUrl;
        }

        try {
            if (!StringUtils.hasText(fileName)) {
                throw new BusinessException(400, "文件名不能为空");
            }

            // 检查文件是否存在
            try {
                HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
                        .bucket(bucketName)
                        .key(fileName)
                        .build();

                s3Client.headObject(headObjectRequest);
            } catch (NoSuchKeyException e) {
                throw new BusinessException(404, "文件不存在：" + fileName);
            }

            // 创建预签名下载请求，添加缓存控制
            // 设置缓存时间为1天（86400秒）
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileName)
                    .responseCacheControl("public, max-age=86400") // 添加缓存控制响应头
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(expiration))
                    .getObjectRequest(getObjectRequest)
                    .build();

            // 生成预签名URL
            String presignedUrl = s3Presigner.presignGetObject(presignRequest).url().toString();

            // 缓存预签名URL，过期时间设置为比预签名URL短一些，确保缓存的URL仍然有效
            cacheService.putAsync(CommonCacheConfig.FILE_URL_CACHE, fileName, presignedUrl, Math.min(expiration - 5, CACHE_EXPIRATION_MINUTES));

            return presignedUrl;
        } catch (S3Exception e) {
            if (e instanceof NoSuchKeyException) {
                throw new BusinessException(404, "文件不存在：" + fileName);
            }
            throw new BusinessException(500, "生成预签名URL失败：" + e.getMessage());
        }
    }

    @Override
    public void deleteFile(String fileName) throws BusinessException {
        try {
            if (!StringUtils.hasText(fileName)) {
                throw new BusinessException(400, "文件名不能为空");
            }

            // 检查文件是否存在
            try {
                HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
                        .bucket(bucketName)
                        .key(fileName)
                        .build();

                s3Client.headObject(headObjectRequest);
            } catch (NoSuchKeyException e) {
                // 如果文件不存在，直接返回，不抛出异常
                return;
            }

            // 删除文件
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileName)
                    .build();

            s3Client.deleteObject(deleteObjectRequest);

            // 清除缓存
            cacheService.evictAsync(CommonCacheConfig.FILE_URL_CACHE, fileName);
        } catch (S3Exception e) {
            throw new BusinessException(500, "删除文件失败：" + e.getMessage());
        }
    }

    /**
     * 检查文件类型是否有效
     */
    private boolean isValidFileType(String extension) {
        if (extension == null) {
            return false;
        }
        List<String> types = Arrays.asList(allowedTypes.split(","));
        return types.contains(extension.toLowerCase());
    }

    /**
     * 生成唯一的文件名
     */
    private String generateFileName(Long userId, String fileType, String extension) {
        return fileType + "/" + userId + "/" + UUID.randomUUID().toString().replaceAll("-", "") + "." + extension;
    }

    /**
     * 从ContentType中提取文件扩展名
     */
    private String getExtensionFromContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return null;
        }

        switch (contentType.toLowerCase()) {
            case "image/jpeg":
                return "jpg";
            case "image/png":
                return "png";
            case "image/gif":
                return "gif";
            case "image/webp":
                return "webp";
            default:
                return null;
        }
    }
}