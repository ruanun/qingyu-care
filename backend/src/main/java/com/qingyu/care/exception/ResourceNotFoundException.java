package com.qingyu.care.exception;

/**
 * 资源不存在异常 (404)
 */
public class ResourceNotFoundException extends BusinessException {
    
    public ResourceNotFoundException(String resourceType, String identifier) {
        super(404, resourceType + " 不存在: " + identifier);
    }
    
    public ResourceNotFoundException(String message) {
        super(404, message);
    }
}
