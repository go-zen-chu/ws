FROM archlinux:base-20211114.0.39041

# pacman -Scc | https://wiki.archlinux.org/title/pacman#Cleaning_the_package_cache
RUN pacman -Sy && \
    pacman -S --noconfirm \
    curl \
    diffutils \
    zsh \
    tmux \
    vim \
    git \
    tig \
    rsync \
    nmap \
    jq \
    fzf \
    kubectl \
    && \
    pacman -Scc

# setup dotfiles
COPY ./dotfiles /root/dotfiles
COPY ./dotfiles/zsh/.zshrc /root/.zshrc
COPY ./dotfiles/tmux/.tmux.conf /root/.tmux.conf
COPY ./dotfiles/vim/.vimrc /root/.vimrc

RUN pushd /root/dotfiles && \
    ./tmux/setup.sh && \
    ./vim/setup.sh && \
    ./git/setup.sh && \
    popd

WORKDIR /root
