package com.example.user.controller;

import com.example.user.command.UserCreateCommand;
import com.example.user.command.UserPageQueryCommand;
import com.example.user.command.UserUpdateCommand;
import com.example.user.dto.UserCreateRequestDTO;
import com.example.user.dto.UserInfoDTO;
import com.example.user.dto.UserQueryDTO;
import com.example.user.dto.UserUpdateRequestDTO;
import com.example.shared.exception.BusinessException;
import com.example.shared.response.ApiResponse;
import com.example.shared.response.PageResult;
import com.example.file.service.FileService;
import com.example.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/admin")
public class AdminUserController {

    @Autowired
    private UserService userService;

    @DubboReference
    private FileService fileService;

    /**
     * 分页查询用户接口，只有拥有 ADMIN 角色的用户可以访问。
     * 返回UserInfoDTO对象，只包含前端需要的字段，不包含敏感信息。
     * 用户头像URL的处理已移至服务层。
     */
    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<PageResult<UserInfoDTO>>> listUsers(UserQueryDTO queryDTO) {
        // 创建查询命令对象
        UserPageQueryCommand command = new UserPageQueryCommand();
        BeanUtils.copyProperties(queryDTO, command);

        // 获取用户分页数据（已在服务层处理头像URL）
        PageResult<UserInfoDTO> result = userService.getUserInfoPage(command);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 添加新用户接口，只有拥有 ADMIN 角色的用户可以访问。
     */
    @PostMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserInfoDTO>> addUser(@RequestBody UserCreateRequestDTO requestDTO) {
        // 创建命令对象
        UserCreateCommand command = new UserCreateCommand();
        BeanUtils.copyProperties(requestDTO, command);

        // 强制角色为普通用户
        command.setRole("USER");

        // 直接使用命令对象调用服务方法
        UserInfoDTO createdUser = userService.createUser(command);
        return ResponseEntity.ok(ApiResponse.success(createdUser));
    }

    /**
     * 更新用户信息接口，只有拥有 ADMIN 角色的用户可以访问。
     */
    @PutMapping("/users/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserInfoDTO>> updateUser(
            @PathVariable Long userId,
            @RequestBody UserUpdateRequestDTO requestDTO) {
        // 创建命令对象
        UserUpdateCommand command = UserUpdateCommand.withUserId(userId);

        // 复制请求数据到命令对象
        BeanUtils.copyProperties(requestDTO, command);

        // 调用服务方法更新用户
        boolean success = userService.updateUser(command);
        if (!success) {
            throw new BusinessException(500, "更新用户信息失败");
        }

        // 管理员接口需要返回完整用户信息，所以查询一次
        UserInfoDTO updatedUser = userService.getUserById(userId);
        return ResponseEntity.ok(ApiResponse.success(updatedUser));
    }

    /**
     * 更新用户状态接口，只有拥有 ADMIN 角色的用户可以访问。
     */
    @PutMapping("/users/{userId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Boolean>> updateUserStatus(
            @PathVariable Long userId,
            @RequestParam Integer status) {
        boolean result = userService.updateUserStatus(userId, status);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * 获取用户详情接口，只有拥有 ADMIN 角色的用户可以访问
     */
    @GetMapping("/users/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserInfoDTO>> getUserDetail(@PathVariable Long userId) {
        UserInfoDTO userInfo = userService.getUserById(userId);
        if (userInfo == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return ResponseEntity.ok(ApiResponse.success(userInfo));
    }
}
