#!/bin/bash
input=$(cat)
eval "$(echo "$input" | jq -r '@sh "MODEL=\(.model.display_name) DIR=\(.workspace.current_dir) PCT=\(.context_window.used_percentage // 0 | floor)"')"

# Collapse the home directory to ~
DIR="${DIR/#$HOME/~}"

# Git branch
BRANCH=$(git branch --show-current 2>/dev/null)
[ -n "$BRANCH" ] && BRANCH=" \033[35m($BRANCH)\033[0m"

# Context color: green -> orange -> red
if [ "$PCT" -lt 50 ]; then
  PCT_COLOR="\033[32m"   # green
elif [ "$PCT" -lt 75 ]; then
  PCT_COLOR="\033[33m"   # yellow/orange
elif [ "$PCT" -lt 90 ]; then
  PCT_COLOR="\033[38;5;208m"  # orange
else
  PCT_COLOR="\033[31m"   # red
fi

echo -e "\033[36m[$MODEL]\033[0m \033[34m$DIR\033[0m$BRANCH \033[90m│\033[0m ${PCT_COLOR}$PCT%\033[0m"
