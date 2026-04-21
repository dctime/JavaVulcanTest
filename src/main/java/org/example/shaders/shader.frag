#version 450

layout(location = 0) in vec3 fragColor; // 值透過內插法每個點都不一樣

layout(location = 0) out vec4 outColor;

void main() {
    outColor = vec4(fragColor, 1.0); // 自動內插
}